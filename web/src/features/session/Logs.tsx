import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, Copy, Download } from "lucide-react";
import { memo, useEffect, useLayoutEffect, useRef, useState } from "react";
import { readNewLogs, visibleLines, wholeLog, type LogLine, type LogState } from "@/api/logs";
import { keys } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { Card, CardTitle } from "@/components/ui/Card";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState } from "@/components/ui/States";
import { problemMessage } from "@/lib/problems";

/** At most this many lines are in the page at once, however long the log (10 MiB is about 150,000 lines). */
export const LOG_CAP = 2_000;
/** How far "Show earlier" moves back. */
const STEP = 1_000;
/** Every row is one line of the caption size, so moving the window keeps the reader's place exactly. */
const ROW_PX = 16;

/**
 * A session's log (ADR 0019), read as it's written: in the system's monospace font, so nothing is downloaded for it
 * (web/DESIGN.md "Typography"). It follows the end while the reader is at the bottom, offers "Jump to latest" once
 * they've scrolled up, and marks where each new attempt begins. Collapsed by default on a phone.
 */
export function Logs({ id, settled, laptop }: { id: string; settled: boolean; laptop: boolean }) {
  const client = useQueryClient();
  const log = useQuery<LogState>({ queryKey: keys.logs(id), queryFn: skipToken });
  const [open, setOpen] = useState(laptop);
  const [failed, setFailed] = useState<unknown>(null);

  // What's there already, once; afterwards each `logs` event reads the rest (src/api/useLiveSession.ts). A settled
  // session sends no events, so its log is read to the end now.
  useEffect(() => {
    readNewLogs(client, id).then(
      () => setFailed(null),
      (error: unknown) => setFailed(error),
    );
  }, [client, id, settled]);

  const lines = log.data ? visibleLines(log.data) : [];
  const count = lines.filter((line) => line.kind === "line").length;

  return (
    <Card aria-labelledby="logs-title">
      <CardTitle id="logs-title">Logs</CardTitle>
      {!laptop && (
        // Full width, so the count arriving changes nothing around it.
        <Button
          variant="secondary"
          block
          aria-expanded={open}
          aria-controls="logs-panel"
          onClick={() => setOpen(!open)}
          className={open ? "mb-4" : undefined}
        >
          {open
            ? "Hide logs"
            : `Show logs, ${count.toLocaleString("en-IN")} ${count === 1 ? "line" : "lines"}`}
        </Button>
      )}
      <div id="logs-panel" hidden={!open}>
        {log.data ? (
          lines.length === 0 ? (
            <EmptyState title={log.data.complete ? "No log lines" : "No log lines yet"}>
              {log.data.complete
                ? "This session didn't write anything to its log."
                : "They show here a few seconds after the session writes them."}
            </EmptyState>
          ) : (
            <LogPanel id={id} lines={lines} />
          )
        ) : failed ? (
          <div className="flex flex-col items-start gap-3">
            <p className="text-small">{problemMessage(failed)}</p>
            <Button
              variant="secondary"
              onClick={() =>
                void readNewLogs(client, id).then(
                  () => setFailed(null),
                  (error: unknown) => setFailed(error),
                )
              }
            >
              Try again
            </Button>
          </div>
        ) : (
          // The panel and its buttons' row, as they'll load.
          <div role="status" aria-label="Loading the logs" className="flex flex-col gap-3">
            <Skeleton className="h-panel w-full" />
            <Skeleton className="h-touch w-1/3" />
          </div>
        )}
      </div>
    </Card>
  );
}

function LogPanel({ id, lines }: { id: string; lines: LogLine[] }) {
  const scroller = useRef<HTMLDivElement>(null);
  /** The window's end; null follows the newest line. */
  const [end, setEnd] = useState<number | null>(null);
  const [atBottom, setAtBottom] = useState(true);
  const [copied, setCopied] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const shiftBy = useRef(0);

  const last = end ?? lines.length;
  const start = Math.max(0, last - LOG_CAP);
  const shown = lines.slice(start, last);

  useLayoutEffect(() => {
    const element = scroller.current;
    if (!element) {
      return;
    }
    if (shiftBy.current !== 0) {
      // "Show earlier" put lines above what the reader was looking at: keep it in place.
      element.scrollTop += shiftBy.current;
      shiftBy.current = 0;
    } else if (atBottom && end === null) {
      element.scrollTop = element.scrollHeight;
    }
  }, [shown.length, start, last, atBottom, end]);

  const earlier = () => {
    const nextEnd = Math.max(LOG_CAP, last - STEP);
    shiftBy.current = (start - Math.max(0, nextEnd - LOG_CAP)) * ROW_PX;
    setEnd(nextEnd);
    setAtBottom(false);
  };
  const latest = () => {
    setEnd(null);
    setAtBottom(true);
    const element = scroller.current;
    if (element) {
      element.scrollTop = element.scrollHeight;
    }
  };
  const copy = async () => {
    await navigator.clipboard.writeText(
      shown.map((line) => (line.kind === "line" ? line.text : `--- attempt ${line.attempt} ---`)).join("\n"),
    );
    setCopied(true);
    setTimeout(() => setCopied(false), 2_000);
  };
  const download = async () => {
    setDownloading(true);
    try {
      const text = await wholeLog(id);
      const link = document.createElement("a");
      link.href = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
      link.download = `session-${id}.log`;
      link.click();
      URL.revokeObjectURL(link.href);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="relative">
        <div
          ref={scroller}
          role="region"
          aria-label="Session log"
          // A scrolling region must take focus, so it scrolls with the keyboard too (WCAG 2.1.1).
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- see above
          tabIndex={0}
          onScroll={(event) => {
            const element = event.currentTarget;
            const bottom = element.scrollTop + element.clientHeight >= element.scrollHeight - ROW_PX;
            if (bottom !== atBottom) {
              setAtBottom(bottom);
            }
          }}
          className="h-panel overflow-auto rounded-control bg-sunken py-2 font-mono text-caption"
          data-testid="log-panel"
        >
          {start > 0 && (
            <div className="px-3 pb-2">
              <Button variant="quiet" onClick={earlier}>
                Show earlier
              </Button>
            </div>
          )}
          {shown.map((line, i) => (
            <Row key={start + i} line={line} />
          ))}
        </div>
        {(!atBottom || end !== null) && (
          <Button variant="secondary" className="absolute right-3 bottom-3 shadow-float" onClick={latest}>
            <ArrowDown aria-hidden="true" className="size-4" strokeWidth={1.75} />
            Jump to latest
          </Button>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => void copy()}>
          <Copy aria-hidden="true" className="size-4" strokeWidth={1.75} />
          {copied ? "Copied" : "Copy"}
        </Button>
        <Button variant="secondary" busy={downloading} onClick={() => void download()}>
          {!downloading && <Download aria-hidden="true" className="size-4" strokeWidth={1.75} />}
          Download
        </Button>
        <p className="self-center text-caption text-text-muted" aria-live="polite">
          {copied ? `Copied ${shown.length.toLocaleString("en-IN")} lines.` : ""}
        </p>
      </div>
    </div>
  );
}

const Row = memo(function Row({ line }: { line: LogLine }) {
  if (line.kind === "attempt") {
    return (
      <div className="mx-3 flex h-4 items-center gap-2 border-t border-border-strong font-sans text-text-muted">
        <span className="-mt-px bg-sunken pr-2 text-caption">
          Attempt {line.attempt}: moved to another host
        </span>
      </div>
    );
  }
  return <div className="h-4 px-3 whitespace-pre text-text">{line.text}</div>;
});
