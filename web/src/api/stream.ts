/**
 * Server-Sent Events over fetch (ADR 0019): the session's live view is `GET /v1/sessions/{id}/events`, and it needs
 * the bearer token in a header, which EventSource can't send. This is the parser and reader, after the HTML
 * standard's "interpreting an event stream": lines end in CRLF, LF or CR; a blank line ends an event; `:` starts a
 * comment; several `data:` lines join with newlines; an event's text may arrive split at any point, and several
 * may arrive at once.
 */
export interface ServerEvent {
  /** The `event:` field, or "message" when there was none. */
  event: string;
  data: string;
}

export class EventParser {
  private buffer = "";
  /** The last chunk ended in CR: if the next starts with LF, that LF is the same line ending. */
  private afterCR = false;
  private eventType = "";
  private data: string[] | null = null;

  /** The events that this piece of the stream completes. */
  push(chunk: string): ServerEvent[] {
    const events: ServerEvent[] = [];
    let text = chunk;
    if (this.afterCR && text.startsWith("\n")) {
      text = text.slice(1);
    }
    this.afterCR = false;
    const buffer = this.buffer + text;
    let start = 0;
    for (let i = 0; i < buffer.length; i++) {
      const char = buffer.charCodeAt(i);
      if (char !== 10 && char !== 13) {
        continue;
      }
      this.line(buffer.slice(start, i), events);
      if (char === 13) {
        if (i + 1 === buffer.length) {
          this.afterCR = true;
        } else if (buffer.charCodeAt(i + 1) === 10) {
          i++;
        }
      }
      start = i + 1;
    }
    this.buffer = buffer.slice(start);
    return events;
  }

  private line(line: string, events: ServerEvent[]): void {
    if (line === "") {
      if (this.data !== null) {
        events.push({ event: this.eventType || "message", data: this.data.join("\n") });
      }
      this.data = null;
      this.eventType = "";
      return;
    }
    if (line.startsWith(":")) {
      return;
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "event") {
      this.eventType = value;
    } else if (field === "data") {
      (this.data ??= []).push(value);
    }
    // `id` and `retry` aren't used: a reconnect starts with a fresh snapshot, and its timing is ours.
  }
}

/** Reads a response's event stream to its end, handing over each event as it completes. */
export async function readEvents(response: Response, onEvent: (event: ServerEvent) => void): Promise<void> {
  if (!response.body) {
    return;
  }
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new EventParser();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      return;
    }
    for (const event of parser.push(value)) {
      onEvent(event);
    }
  }
}
