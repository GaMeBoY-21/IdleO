import { ArrowDown, Copy, Download } from "lucide-react";
import { memo, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { lineTotal, linesBetween, wholeLog, type LogLine, type LogState } from "@/api/logs";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";

/** At most this many lines are in the page at once, however long the log (10 MiB is about 150,000 lines). */
export const LOG_CAP = 2_000;
/** How far "Show earlier" moves back. */
const STEP = 1_000;
/** Every row is one line of the caption size, so moving the window keeps the reader's place exactly. */
const ROW_PX = 18;

/** A button on the log tile: pressed in with the page's colour. */
export const logButton =
  "min-h-touch rounded-full bg-ground px-3.5 text-small font-medium text-text hover:bg-divider focus-visible:outline-focus";

/**
 * A session's log lines in the log tile (web/DESIGN.md "The session screen"): the newest 2,000 at most, in the
 * system's monospace font. It follows the end while the reader is at the bottom, offers "Jump to latest" once they've
 * scrolled up and "Show earlier" at the top, and marks where each new attempt begins. Copy and Download sit in its
 * header, after `title`.
 */
export function LogPanel({
  id,
  log,
  title,
  live,
}: {
  id: string;
  log: LogState;
  title: ReactNode;
  live: boolean;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  /** The window's end; null follows the newest line. */
  const [end, setEnd] = useState<number | null>(null);
  const [atBottom, setAtBottom] = useState(true);
  const [copied, setCopied] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const shiftBy = useRef(0);
  /** Blocks already drawn, by key, so an append rebuilds only the block still growing (see `blocks`). */
  const [drawn] = useState(() => new Map<string, LogBlock>());

  const last = end ?? lineTotal(log);
  const start = Math.max(0, last - LOG_CAP);
  const shown = linesBetween(log, start, last);
  const following = atBottom && end === null;

  useLayoutEffect(() => {
    const element = scroller.current;
    if (!element) {
      return;
    }
    if (shiftBy.current !== 0) {
      // "Show earlier" put lines above what the reader was looking at: keep it in place.
      element.scrollTop += shiftBy.current;
      shiftBy.current = 0;
    } else if (following) {
      element.scrollTop = element.scrollHeight;
    }
  }, [shown.length, start, last, following]);

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
    <>
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1">{title}</div>
        <p className="text-caption text-text-muted" aria-live="polite">
          {copied
            ? `Copied ${shown.length.toLocaleString("en-IN")} lines`
            : following && live
              ? "Following live"
              : ""}
        </p>
        <button type="button" className={logButton} onClick={() => void copy()}>
          <Copy aria-hidden="true" className="mr-1.5 inline size-4" strokeWidth={1.75} />
          Copy
        </button>
        <button
          type="button"
          className={logButton}
          disabled={downloading}
          aria-busy={downloading || undefined}
          onClick={() => void download()}
        >
          <Download aria-hidden="true" className="mr-1.5 inline size-4" strokeWidth={1.75} />
          Download
        </button>
      </div>
      {/* The scroller fills its box absolutely, so lines arriving never make the panels around it lay out again. */}
      <div className="relative min-h-panel flex-1 basis-0 xl:min-h-40">
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
          className="absolute inset-0 overflow-auto rounded-control bg-ground p-3 font-mono text-caption focus-visible:outline-focus"
          data-testid="log-panel"
        >
          {start > 0 && (
            <div className="pb-2">
              <button type="button" className={logButton} onClick={earlier}>
                Show earlier
              </button>
            </div>
          )}
          {blocks(shown, start, drawn).map((block) => (
            <Block key={block.key} block={block} />
          ))}
        </div>
        {!following && (
          <Button variant="primary" className={cn("absolute right-3 bottom-3 shadow-float")} onClick={latest}>
            <ArrowDown aria-hidden="true" className="size-4" strokeWidth={1.75} />
            Jump to latest
          </Button>
        )}
      </div>
    </>
  );
}

/** Lines drawn together: up to 100 in one element, or an attempt's mark on its own. */
export type LogBlock =
  | { key: string; kind: "lines"; text: string; count: number }
  | { key: string; kind: "attempt"; attempt: number };

const BLOCK = 100;

/**
 * The shown lines as blocks of up to 100, cut at the same absolute line numbers whatever the window, so a block
 * keeps its key as the window moves and only the blocks at its ends change. A page of 2,000 lines is about 20
 * elements, not 2,000.
 *
 * A log only grows, so a block that has been closed (by the next block, or an attempt's mark) never changes: the same
 * key and count mean the same lines. Such a block is taken from `drawn` as it was, the same object, so its 100 lines
 * aren't joined again and `Block` (memoised) isn't drawn again. Only the last block, which may still be growing, is
 * rebuilt on an append. `drawn` keeps just this window's closed blocks.
 */
export function blocks(lines: LogLine[], start: number, drawn: Map<string, LogBlock>): LogBlock[] {
  const out: LogBlock[] = [];
  const closedBlocks: LogBlock[] = [];
  let text: string[] = [];
  let first = start;
  const flush = (at: number, closed: boolean) => {
    if (text.length > 0) {
      const key = `l${first}`;
      const before = drawn.get(key);
      const block: LogBlock =
        closed && before?.kind === "lines" && before.count === text.length
          ? before
          : { key, kind: "lines", text: text.join("\n"), count: text.length };
      out.push(block);
      if (closed) {
        closedBlocks.push(block);
      }
      text = [];
    }
    first = at;
  };
  lines.forEach((line, i) => {
    const at = start + i;
    if (line.kind === "attempt") {
      flush(at + 1, true);
      const key = `a${at}`;
      const mark = drawn.get(key) ?? { key, kind: "attempt" as const, attempt: line.attempt };
      out.push(mark);
      closedBlocks.push(mark);
      return;
    }
    if (at % BLOCK === 0) {
      flush(at, true);
    }
    text.push(line.text);
  });
  flush(start + lines.length, false);
  // Only closed blocks are kept: the last one may still grow, even at the same count (its last line, half written).
  drawn.clear();
  for (const block of closedBlocks) {
    drawn.set(block.key, block);
  }
  return out;
}

const Block = memo(function Block({ block }: { block: LogBlock }) {
  if (block.kind === "attempt") {
    return (
      <div className="flex h-4.5 items-center gap-2 border-t border-divider font-sans text-text-muted">
        <span className="-mt-px bg-ground pr-2 text-caption">
          Attempt {block.attempt}: moved to another host
        </span>
      </div>
    );
  }
  // One line of the caption size per line, so moving the window keeps the reader's place exactly. A block out of
  // view isn't laid out at all (content-visibility), held at its exact height meanwhile: laying out all 2,000 lines
  // whenever the window moved or the panel scrolled to the end held a tap back 195 ms on a CPU slowed 40 times.
  return (
    <div
      className="min-h-4.5 whitespace-pre text-text"
      style={{ contentVisibility: "auto", containIntrinsicSize: `auto ${block.count * ROW_PX}px` }}
      data-lines={block.count}
    >
      {block.text}
    </div>
  );
});
