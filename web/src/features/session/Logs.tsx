import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ScrollText } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { lineTotal, linesBetween, readNewLogs, type LogState } from "@/api/logs";
import { keys } from "@/api/queries";
import { useAfterPaint } from "@/lib/after-first-screen";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import { readSignals } from "@/lib/log-signals";
import { LossChart } from "./laptop/LossChart";
import { logButton, LogPanel } from "./LogPanel";

/**
 * A session's log (ADR 0019), read as it's written, in the log tile, a white tile with a monospace body (web/DESIGN.md
 * "The session screen"). On a laptop it fills the column beside the tiles; on a phone it's a "Logs" row showing the newest
 * line, which opens the panel under it.
 */
export function Logs({ id, settled, laptop }: { id: string; settled: boolean; laptop: boolean }) {
  const client = useQueryClient();
  const log = useQuery<LogState>({ queryKey: keys.logs(id), queryFn: skipToken });
  const [open, setOpen] = useState(false);
  const [failed, setFailed] = useState<unknown>(null);

  // What's there already, once; afterwards each `logs` event reads the rest (src/api/useLiveSession.ts). A settled
  // session sends no events, so its log is read to the end now.
  const painted = useAfterPaint();
  const read = useCallback(
    () =>
      readNewLogs(client, id).then(
        () => setFailed(null),
        (error: unknown) => setFailed(error),
      ),
    [client, id],
  );
  useEffect(() => {
    if (painted) {
      void read();
    }
  }, [read, settled, painted]);

  const title = <h2 className="text-section">Logs</h2>;
  const total = log.data ? lineTotal(log.data) : 0;
  const body = log.data ? (
    total === 0 ? (
      <Quiet>
        {log.data.complete
          ? "This session didn't write anything to its log."
          : "No log lines yet. They show here a few seconds after the session writes them."}
      </Quiet>
    ) : (
      <LogPanel id={id} log={log.data} title={laptop ? title : null} live={!settled} />
    )
  ) : failed ? (
    <div className="flex flex-col items-start gap-3">
      <p className="text-small text-text">{problemMessage(failed)}</p>
      <button type="button" className={logButton} onClick={() => void read()}>
        Try again
      </button>
    </div>
  ) : (
    <div role="status" aria-label="Loading the logs" className="flex flex-col gap-3">
      {["w-2/3", "w-1/2", "w-3/4"].map((width) => (
        <span key={width} aria-hidden="true" className={cn("skeleton h-3 rounded-full", width)} />
      ))}
    </div>
  );
  const signals = readSignals(id, log.data);
  const panel = (
    <Tile
      key={log.data ? "log" : "loading"}
      as="section"
      aria-label="Logs"
      className={cn("flex flex-col gap-4", laptop ? "min-h-0 flex-1 px-6 py-5" : "p-4")}
    >
      {laptop && total === 0 && title}
      {body}
    </Tile>
  );
  if (laptop) {
    // The chart, when the log carries loss values, above the log, which keeps the rest of the column. While the log is
    // unread there is only the panel; when it arrives, a new panel is drawn (its key changes), so nothing moves.
    return (
      <div className="flex min-h-0 flex-1 flex-col gap-4 2xl:gap-5">
        {log.data && <LossChart losses={signals.losses} />}
        {panel}
      </div>
    );
  }

  const newest = log.data && total > 0 ? linesBetween(log.data, total - 1, total)[0] : undefined;
  const count = log.data?.count ?? 0;
  return (
    <div className="flex flex-col">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="logs-panel"
        onClick={() => setOpen(!open)}
        className="flex min-h-14 items-center gap-3 rounded-tile bg-fill px-4.5 py-3.5 text-left text-body"
      >
        <ScrollText aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
        <span className="min-w-0 flex-1 truncate">
          <span className="font-semibold">Logs</span>
          <span className="font-medium text-text-muted">
            {newest?.kind === "line"
              ? ` · ${newest.text}`
              : ` · ${count.toLocaleString("en-IN")} ${count === 1 ? "line" : "lines"}`}
          </span>
        </span>
        <ChevronDown
          aria-hidden="true"
          className={cn("size-4.5 shrink-0 transition-transform duration-120", open && "rotate-180")}
          strokeWidth={2}
        />
      </button>
      <div id="logs-panel" hidden={!open} className="pt-2.5">
        {panel}
      </div>
    </div>
  );
}

function Quiet({ children }: { children: string }) {
  return <p className="text-small text-text-muted">{children}</p>;
}
