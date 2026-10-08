import type { QueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { keys } from "./queries";
import type { components } from "./schema.gen";

type LogChunk = components["schemas"]["LogChunk"];
type LogPage = components["schemas"]["SessionLogPage"];

/** One line of a session's log, or the mark where a new attempt's log begins. */
export type LogLine = { kind: "line"; text: string } | { kind: "attempt"; attempt: number };

/** What the screen holds of a session's log: its lines in order, and where to continue reading. */
export interface LogState {
  lines: LogLine[];
  /** The API's cursor after the last chunk read; null before the first read and once the log is complete. */
  cursor: string | null;
  complete: boolean;
  /** The last attempt and chunk read, so a `logs` event for one already read fetches nothing. */
  attempt: number;
  seq: number;
  /** The end of the last chunk, when it stopped mid-line. */
  partial: string;
}

export const EMPTY_LOG: LogState = {
  lines: [],
  cursor: null,
  complete: false,
  attempt: 0,
  seq: 0,
  partial: "",
};

/** The newest chunks' lines added to a log: whole lines only, attempt changes marked. */
export function appendChunks(state: LogState, chunks: LogChunk[]): LogState {
  const lines = state.lines.slice();
  let { attempt, seq, partial } = state;
  for (const chunk of chunks) {
    if (chunk.attempt !== attempt) {
      if (partial !== "") {
        lines.push({ kind: "line", text: partial });
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
    seq = chunk.seq;
  }
  return { ...state, lines, attempt, seq, partial };
}

/** The lines to show: the whole ones, then a last line still being written. */
export const visibleLines = (state: LogState): LogLine[] =>
  state.partial === "" ? state.lines : [...state.lines, { kind: "line", text: state.partial }];

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

async function readToEnd(client: QueryClient, id: string): Promise<void> {
  for (;;) {
    const state = client.getQueryData<LogState>(keys.logs(id)) ?? EMPTY_LOG;
    if (state.complete) {
      return;
    }
    const page = await data(
      api.GET("/v1/sessions/{id}/logs", {
        params: { path: { id }, query: { limit: PAGE, ...(state.cursor ? { cursor: state.cursor } : {}) } },
      }),
    );
    const next = appendChunks(state, page.chunks);
    client.setQueryData<LogState>(keys.logs(id), {
      ...next,
      cursor: page.nextCursor ?? null,
      complete: page.complete,
    });
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
