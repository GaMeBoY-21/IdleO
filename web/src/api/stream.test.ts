import { describe, expect, it } from "vitest";
import { EventParser, readEvents, type ServerEvent } from "./stream";

const STREAM =
  ": connected\r\n" +
  "event: session\r\n" +
  'data: {"id":"a","state":"RUNNING"}\r\n' +
  "\r\n" +
  ":keep-alive\n\n" +
  "event: logs\n" +
  'data: {"attempt":1,\n' +
  'data: "latestSeq":3}\n' +
  "\n" +
  "data: plain\r" +
  "\r" +
  "event: session\n" +
  "data:no-space\n" +
  "\n";

const EXPECTED: ServerEvent[] = [
  { event: "session", data: '{"id":"a","state":"RUNNING"}' },
  { event: "logs", data: '{"attempt":1,\n"latestSeq":3}' },
  { event: "message", data: "plain" },
  { event: "session", data: "no-space" },
];

const parseAll = (chunks: string[]) => {
  const parser = new EventParser();
  return chunks.flatMap((chunk) => parser.push(chunk));
};

describe("the event stream parser", () => {
  it("reads events, comments, multi-line data, and every line ending", () => {
    expect(parseAll([STREAM])).toEqual(EXPECTED);
  });

  it("gives the same events however the stream is split in two", () => {
    for (let at = 0; at <= STREAM.length; at++) {
      expect(parseAll([STREAM.slice(0, at), STREAM.slice(at)]), `split at ${at}`).toEqual(EXPECTED);
    }
  });

  it("gives the same events a character at a time", () => {
    expect(parseAll(Array.from({ length: STREAM.length }, (_, i) => STREAM.charAt(i)))).toEqual(EXPECTED);
  });

  it("treats a CR, then an LF in the next chunk, as one line ending", () => {
    expect(parseAll(["data: a\r", "\n", "\r", "\ndata: b\n\n"])).toEqual([
      { event: "message", data: "a" },
      { event: "message", data: "b" },
    ]);
  });

  it("dispatches nothing for an event without data, and keeps an unfinished one for later", () => {
    const parser = new EventParser();
    expect(parser.push("event: session\n\n")).toEqual([]);
    expect(parser.push("data: half")).toEqual([]);
    expect(parser.push(" done\n\n")).toEqual([{ event: "message", data: "half done" }]);
  });

  it("reads a fetch response's body to its end", async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        // A multi-byte character split across chunks too.
        const bytes = encoder.encode('event: session\ndata: {"name":"₹"}\n\n');
        controller.enqueue(bytes.slice(0, 27));
        controller.enqueue(bytes.slice(27));
        controller.close();
      },
    });
    const events: ServerEvent[] = [];
    await readEvents(new Response(body), (event) => events.push(event));
    expect(events).toEqual([{ event: "session", data: '{"name":"₹"}' }]);
  });
});
