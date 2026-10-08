import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { appendChunks, EMPTY_LOG, readNewLogs, visibleLines, type LogState } from "./logs";
import { keys } from "./queries";

const get = vi.hoisted(() => vi.fn());
vi.mock("./client", () => ({
  api: { GET: get },
  data: async (result: Promise<{ data: unknown }>) => (await result).data,
}));

const chunk = (attempt: number, seq: number, text: string) => ({ attempt, seq, text });

describe("a session's log", () => {
  afterEach(() => get.mockReset());

  it("keeps whole lines, joins a line split across chunks, and marks a new attempt", () => {
    const state = appendChunks(EMPTY_LOG, [
      chunk(1, 1, "starting\nstep 1"),
      chunk(1, 2, "0\nstep 20\n"),
      chunk(2, 1, "resumed from checkpoint 3\n"),
    ]);
    expect(visibleLines(state)).toEqual([
      { kind: "line", text: "starting" },
      { kind: "line", text: "step 10" },
      { kind: "line", text: "step 20" },
      { kind: "attempt", attempt: 2 },
      { kind: "line", text: "resumed from checkpoint 3" },
    ]);
    expect([state.attempt, state.seq, state.count]).toEqual([2, 1, 4]);
  });

  it("shows a line still being written, without keeping it twice", () => {
    const state = appendChunks(EMPTY_LOG, [chunk(1, 1, "a\nhalf")]);
    expect(visibleLines(state).at(-1)).toEqual({ kind: "line", text: "half" });
    expect(visibleLines(appendChunks(state, [chunk(1, 2, " done\n")])).at(-1)).toEqual({
      kind: "line",
      text: "half done",
    });
  });

  it("reads from where it left off, never again what it has", async () => {
    const client = new QueryClient();
    get
      .mockResolvedValueOnce({ data: { chunks: [chunk(1, 1, "one\n")], nextCursor: "c1", complete: false } })
      .mockResolvedValueOnce({ data: { chunks: [chunk(1, 2, "two\n")], nextCursor: "c2", complete: false } })
      .mockResolvedValueOnce({ data: { chunks: [], nextCursor: null, complete: true } });
    await readNewLogs(client, "s");
    await readNewLogs(client, "s");
    await readNewLogs(client, "s");
    await readNewLogs(client, "s");
    expect(
      get.mock.calls.map(
        (call) => (call[1] as { params: { query: { cursor?: string } } }).params.query.cursor,
      ),
    ).toEqual([undefined, "c1", "c2"]);
    const state = client.getQueryData<LogState>(keys.logs("s"))!;
    expect(visibleLines(state).map((line) => (line.kind === "line" ? line.text : ""))).toEqual([
      "one",
      "two",
    ]);
    expect(state.complete).toBe(true);
  });

  it("reads again after a read in flight when another event arrives, one read at a time", async () => {
    const client = new QueryClient();
    let release!: () => void;
    get
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = () =>
              resolve({ data: { chunks: [chunk(1, 1, "one\n")], nextCursor: "c1", complete: false } });
          }),
      )
      .mockResolvedValueOnce({ data: { chunks: [chunk(1, 2, "two\n")], nextCursor: "c2", complete: false } });
    const first = readNewLogs(client, "s");
    const second = readNewLogs(client, "s");
    expect(get).toHaveBeenCalledTimes(1);
    release();
    await Promise.all([first, second]);
    expect(get).toHaveBeenCalledTimes(2);
    expect(client.getQueryData<LogState>(keys.logs("s"))!.seq).toBe(2);
  });
});
