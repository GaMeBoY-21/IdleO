import { ApiError } from "./errors";
import type { Session } from "./queries";
import { readEvents } from "./stream";
import type { components } from "./schema.gen";

export type LogsEvent = components["schemas"]["SessionLogsEvent"];

/**
 * How the live view stands:
 * - `connecting`: opening the stream, before its first snapshot;
 * - `live`: the stream is open;
 * - `down`: the stream dropped and reconnecting failed; the screen polls and says its figures may be stale;
 * - `ended`: the session is settled, so nothing will change; the stream is closed for good.
 */
export type StreamStatus = "connecting" | "live" | "down" | "ended";

export interface FollowerDeps {
  /** Opens the stream with a fresh token (src/api/authed-fetch.ts). */
  open: (signal: AbortSignal) => Promise<Response>;
  onSession: (session: Session) => void;
  onLogs: (event: LogsEvent) => void;
  onStatus: (status: StreamStatus) => void;
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  random?: () => number;
}

const MIN_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

/** The wait before reconnect attempt `n` (1, 2, …): doubling from 1 s to 30 s, each between half and all of it. */
export function backoff(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * 2 ** (attempt - 1));
  return Math.max(MIN_BACKOFF_MS, Math.round(ceiling * (0.5 + random() / 2)));
}

const wait = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });

/**
 * Follows one session over its event stream until it's settled (ADR 0019):
 * - every `session` event goes to `onSession`, and every `logs` event to `onLogs`;
 * - the server closes the stream after 30 minutes: it reopens at once, with a fresh token;
 * - a dropped stream is retried after 1 to 30 seconds of backoff with jitter; when a retry fails, it reports
 *   `down`, and keeps retrying until the stream is back;
 * - once a snapshot says `settled`, it stops for good;
 * - a session that's gone (404) isn't retried: the screen's own query says so.
 */
export async function followSession(deps: FollowerDeps, signal: AbortSignal): Promise<void> {
  const sleep = deps.sleep ?? wait;
  const random = deps.random ?? Math.random;
  // Changed from inside the stream's callbacks, so kept in one object rather than narrowed locals.
  const state = { settled: false, failures: 0, wasLive: false, first: true };
  const snapshotless = () => state.first;
  const aborted = () => signal.aborted;
  deps.onStatus("connecting");

  while (!signal.aborted && !state.settled) {
    try {
      const response = await deps.open(signal);
      if (!response.ok) {
        throw ApiError.of(response.status, await response.json().catch(() => null));
      }
      state.first = true;
      await readEvents(response, (event) => {
        if (event.event === "session") {
          const session = JSON.parse(event.data) as Session;
          state.settled ||= session.settled;
          deps.onSession(session);
          if (state.first) {
            state.first = false;
            state.failures = 0;
            state.wasLive = true;
            deps.onStatus("live");
          }
        } else if (event.event === "logs") {
          deps.onLogs(JSON.parse(event.data) as LogsEvent);
        }
      });
      // The server closed the stream: settled, or its 30 minutes were up. Reopen at once unless it's settled; one
      // that closed before its first snapshot failed, and waits like any other failure.
      if (snapshotless()) {
        throw new Error("the stream closed before its first snapshot");
      }
    } catch (error) {
      if (aborted()) {
        return;
      }
      if (error instanceof ApiError && error.status === 404) {
        return;
      }
      state.failures++;
      // The first retry after a drop is quiet; when a retry fails too, or the stream never opened, it's down.
      if (state.failures > 1 || !state.wasLive) {
        deps.onStatus("down");
      }
      await sleep(backoff(state.failures, random), signal);
    }
  }
  if (state.settled) {
    deps.onStatus("ended");
  }
}
