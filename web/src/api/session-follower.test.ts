import { describe, expect, it, vi } from "vitest";
import type { Session } from "./queries";
import { backoff, followSession, type StreamStatus } from "./session-follower";

const sessionEvent = (settled: boolean, state = "RUNNING") =>
  `event: session\ndata: ${JSON.stringify({ id: "s", state, settled })}\n\n`;

/** A response whose body sends these texts, then ends cleanly or fails as a dropped connection does. */
function stream(texts: string[], end: "close" | "drop" = "close"): Response {
  const encoder = new TextEncoder();
  let next = 0;
  return new Response(
    // Pulled one text at a time, so a drop comes after what was sent, as on the wire.
    new ReadableStream<Uint8Array>({
      pull(controller) {
        const text = texts[next++];
        if (text !== undefined) {
          controller.enqueue(encoder.encode(text));
        } else if (end === "close") {
          controller.close();
        } else {
          controller.error(new TypeError("network error"));
        }
      },
    }),
    { status: 200, headers: { "Content-Type": "text/event-stream" } },
  );
}

function harness(responses: (() => Response | Promise<Response>)[], { streamMs = 60_000 } = {}) {
  const statuses: StreamStatus[] = [];
  const sessions: Session[] = [];
  const logs: unknown[] = [];
  const waits: number[] = [];
  let opened = 0;
  let clock = 0;
  let advance = false;
  const controller = new AbortController();
  const open = vi.fn(async () => {
    const next = responses[opened++];
    // The stream opened now, and stays open for streamMs.
    advance = true;
    if (!next) {
      controller.abort();
      throw new Error("no more");
    }
    return next();
  });
  const run = followSession(
    {
      open,
      onSession: (session) => sessions.push(session),
      onLogs: (event) => logs.push(event),
      onStatus: (status) => statuses.push(status),
      sleep: async (ms) => {
        waits.push(ms);
        clock += ms;
      },
      random: () => 1,
      now: () => {
        const at = clock;
        if (advance) {
          advance = false;
          clock += streamMs;
        }
        return at;
      },
    },
    controller.signal,
  );
  return { run, statuses, sessions, logs, waits, open };
}

describe("following a session", () => {
  it("passes on snapshots and log events, and stops for good once settled", async () => {
    const h = harness([
      () =>
        stream([
          sessionEvent(false),
          'event: logs\ndata: {"attempt":1,"latestSeq":2}\n\n',
          sessionEvent(true),
        ]),
    ]);
    await h.run;
    expect(h.sessions.map((s) => s.settled)).toEqual([false, true]);
    expect(h.logs).toEqual([{ attempt: 1, latestSeq: 2 }]);
    expect(h.statuses).toEqual(["connecting", "live", "ended"]);
    expect(h.open).toHaveBeenCalledTimes(1);
  });

  it("reopens at once, with a fresh request, when the server closes after its 30 minutes", async () => {
    const h = harness([() => stream([sessionEvent(false)]), () => stream([sessionEvent(true)])]);
    await h.run;
    expect(h.open).toHaveBeenCalledTimes(2);
    expect(h.waits).toEqual([]);
    expect(h.statuses).toEqual(["connecting", "live", "live", "ended"]);
  });

  it("retries a dropped stream quietly once, then says it's down until it's back", async () => {
    const h = harness([
      () => stream([sessionEvent(false)], "drop"),
      () => Promise.reject(new TypeError("offline")),
      () => Promise.reject(new TypeError("offline")),
      () => stream([sessionEvent(true)]),
    ]);
    await h.run;
    expect(h.statuses).toEqual(["connecting", "live", "down", "down", "live", "ended"]);
    expect(h.waits).toEqual([1_000, 2_000, 4_000]);
  });

  it("is down at once when the stream never opens, and treats too-many-streams like any failure", async () => {
    const h = harness([
      () => new Response(JSON.stringify({ type: "/problems/too-many-streams" }), { status: 429 }),
      () => stream([sessionEvent(true)]),
    ]);
    await h.run;
    expect(h.statuses).toEqual(["connecting", "down", "live", "ended"]);
  });

  it("gives up on a session that's gone", async () => {
    const h = harness([() => new Response(JSON.stringify({ type: "/problems/not-found" }), { status: 404 })]);
    await h.run;
    expect(h.open).toHaveBeenCalledTimes(1);
    expect(h.statuses).toEqual(["connecting"]);
  });

  it("pauses before reopening a stream that closed within seconds, without calling it down", async () => {
    const h = harness([() => stream([sessionEvent(false)]), () => stream([sessionEvent(true)])], {
      streamMs: 2_000,
    });
    await h.run;
    expect(h.waits).toEqual([1_000]);
    expect(h.statuses).toEqual(["connecting", "live", "live", "ended"]);
  });

  it("waits like a failure when a stream closes before any snapshot", async () => {
    const h = harness([() => stream([": nothing\n\n"]), () => stream([sessionEvent(true)])]);
    await h.run;
    expect(h.waits).toEqual([1_000]);
  });
});

describe("the backoff", () => {
  it("doubles from 1 to 30 seconds, with jitter of up to half", () => {
    expect([1, 2, 3, 4, 5, 6, 7, 10].map((n) => backoff(n, () => 1))).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000,
    ]);
    expect(backoff(6, () => 0)).toBe(15_000);
    expect(backoff(1, () => 0)).toBe(1_000);
  });
});
