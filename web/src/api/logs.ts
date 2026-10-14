import type { QueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { keys } from "./queries";
import type { components } from "./schema.gen";

type LogChunk = components["schemas"]["LogChunk"];
type LogPage = components["schemas"]["SessionLogPage"];

/** One line of a session's log, or the mark where a new attempt's log begins. */
export type LogLine = { kind: "line"; text: string } | { kind: "attempt"; attempt: number };

/**
 * What the screen holds of a session's log: its lines in order, and where to continue reading. The lines are kept as
 * the segments they arrived in, so adding some never copies the ones before: a 10 MiB log is 150,000 lines.
 */
export interface LogState {
  segments: readonly (readonly LogLine[])[];
  /** How many lines (and attempt marks) the segments hold in all. */
  length: number;
  /** The API's cursor after the last chunk read; null before the first read and once the log is complete. */
  cursor: string | null;
  complete: boolean;
  /** The last attempt and chunk read, so a `logs` event for one already read fetches nothing. */
  attempt: number;
  seq: number;
  /** The end of the last chunk, when it stopped mid-line. */
  partial: string;
  /** How many lines of text (not attempt marks) there are, kept as they arrive rather than counted each time. */
  count: number;
}

export const EMPTY_LOG: LogState = {
  segments: [],
  length: 0,
  cursor: null,
  complete: false,
  attempt: 0,
  seq: 0,
  partial: "",
  count: 0,
};

/**
 * The newest chunks' lines added to a log: whole lines only, attempt changes marked. Only the new lines are touched:
 * they become one more segment, and the lines before are shared, never copied. Nothing measures a line's text.
 */
export function appendChunks(state: LogState, chunks: LogChunk[]): LogState {
  const lines: LogLine[] = [];
  let { attempt, seq, partial, count } = state;
  for (const chunk of chunks) {
    if (chunk.attempt !== attempt) {
      if (partial !== "") {
        lines.push({ kind: "line", text: partial });
        count++;
        partial = "";
      }
      if (attempt !== 0 || chunk.attempt > 1) {
        lines.push({ kind: "attempt", attempt: chunk.attempt });
      }
      attempt = chunk.attempt;
    }
    const parts = (partial + chunk.text).split("\n");
    partial = parts.pop() ?? "";
    for (const text of parts) {
      lines.push({ kind: "line", text });
    }
    count += parts.length;
    seq = chunk.seq;
  }
  const segments = lines.length > 0 ? [...state.segments, lines] : state.segments;
  return { ...state, segments, length: state.length + lines.length, attempt, seq, partial, count };
}

/** How many lines there are to show: the whole ones, then a last line still being written. */
export const lineTotal = (state: LogState) => state.length + (state.partial === "" ? 0 : 1);
/** Lines `start` to `end` of what's shown, without copying the rest of a long log. */
export function linesBetween(state: LogState, start: number, end: number): LogLine[] {
  const shown: LogLine[] = [];
  const stop = Math.min(end, state.length);
  let offset = 0;
  for (const segment of state.segments) {
    const next = offset + segment.length;
    if (next > start && offset < stop) {
      shown.push(...segment.slice(Math.max(0, start - offset), Math.min(segment.length, stop - offset)));
    }
    if (next >= stop) {
      break;
    }
    offset = next;
  }
  if (state.partial !== "" && end > state.length) {
    shown.push({ kind: "line", text: state.partial });
  }
  return shown;
}
/** Every line to show; for tests and short logs. */
export const visibleLines = (state: LogState): LogLine[] => linesBetween(state, 0, lineTotal(state));

const PAGE = 100;
const running = new Map<string, { again: boolean; done: Promise<void> }>();

/**
 * Reads a session's log from where the screen left off, never again what it already has, and keeps it in the query
 * cache. One read per session at a time: a `logs` event that arrives while one is reading reads again after it.
 */
export function readNewLogs(client: QueryClient, id: string): Promise<void> {
  const current = running.get(id);
  if (current) {
    current.again = true;
    return current.done;
  }
  const entry = { again: false, done: Promise.resolve() };
  entry.done = (async () => {
    try {
      const again = () => entry.again;
      do {
        entry.again = false;
        await readToEnd(client, id);
      } while (again());
    } finally {
      running.delete(id);
    }
  })();
  running.set(id, entry);
  return entry.done;
}

/** About one frame's worth of work: a slice of chunks stops once it has taken this long, and the browser goes next. */
const SLICE_MS = 8;

/** Lets the browser handle input and paint before the next slice: `scheduler.yield()` where there is one. */
function yieldToBrowser(): Promise<void> {
  const scheduler = (globalThis as { scheduler?: { yield?: () => Promise<void> } }).scheduler;
  return scheduler?.yield ? scheduler.yield() : new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * Writes a log's newest state to the query cache at most once an animation frame, however many slices or pages
 * arrive in between, so React draws the panel once a frame at most. A hidden tab gets no frames, so a timer stands
 * in after 100 ms. The returned promise settles once this state, or a newer one, is in the cache.
 */
const pending = new Map<string, { state: LogState; written: Promise<void> }>();
function writeOncePerFrame(client: QueryClient, id: string, state: LogState): Promise<void> {
  const waiting = pending.get(id);
  if (waiting) {
    waiting.state = state;
    return waiting.written;
  }
  const entry = { state, written: Promise.resolve() };
  entry.written = new Promise((resolve) => {
    let done = false;
    const write = () => {
      if (done) {
        return;
      }
      done = true;
      pending.delete(id);
      client.setQueryData<LogState>(keys.logs(id), entry.state);
      resolve();
    };
    if (typeof requestAnimationFrame === "function") {
      requestAnimationFrame(write);
    }
    setTimeout(write, 100);
  });
  pending.set(id, entry);
  return entry.written;
}

/** The newest state of a log: one waiting for its frame, else the cache's. */
const latest = (client: QueryClient, id: string): LogState =>
  pending.get(id)?.state ?? client.getQueryData<LogState>(keys.logs(id)) ?? EMPTY_LOG;

async function readToEnd(client: QueryClient, id: string): Promise<void> {
  for (;;) {
    let state = latest(client, id);
    if (state.complete) {
      return;
    }
    const page = await data(
      api.GET("/v1/sessions/{id}/logs", {
        params: { path: { id }, query: { limit: PAGE, ...(state.cursor ? { cursor: state.cursor } : {}) } },
      }),
    );
    // A page can hold 100 chunks of 1,000 lines: it's added a slice at a time, each slice stopping after about
    // SLICE_MS of work, with the browser free to handle a tap or a key between slices.
    state = latest(client, id);
    let next = 0;
    let written = Promise.resolve();
    while (next < page.chunks.length) {
      const began = performance.now();
      const from = next;
      do {
        next++;
      } while (next < page.chunks.length && performance.now() - began < SLICE_MS);
      state = appendChunks(state, page.chunks.slice(from, next));
      const last = next === page.chunks.length;
      written = writeOncePerFrame(
        client,
        id,
        last ? { ...state, cursor: page.nextCursor ?? null, complete: page.complete } : state,
      );
      if (!last) {
        await yieldToBrowser();
      }
    }
    if (page.chunks.length === 0) {
      written = writeOncePerFrame(client, id, {
        ...state,
        cursor: page.nextCursor ?? null,
        complete: page.complete,
      });
    }
    await written;
    if (page.complete || page.chunks.length < PAGE) {
      return;
    }
  }
}

/** Every chunk of a session's log from the start, as one text, for "Download". */
export async function wholeLog(id: string): Promise<string> {
  const texts: string[] = [];
  let cursor: string | null = null;
  let attempt = 0;
  for (;;) {
    const page: LogPage = await data(
      api.GET("/v1/sessions/{id}/logs", {
        params: { path: { id }, query: { limit: PAGE, ...(cursor ? { cursor } : {}) } },
      }),
    );
    for (const chunk of page.chunks) {
      if (chunk.attempt !== attempt && attempt !== 0) {
        texts.push(`\n--- attempt ${chunk.attempt} ---\n`);
      }
      attempt = chunk.attempt;
      texts.push(chunk.text);
    }
    cursor = page.nextCursor ?? null;
    if (page.complete || page.chunks.length < PAGE || cursor === null) {
      return texts.join("");
    }
  }
}
