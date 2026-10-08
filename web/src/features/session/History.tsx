import { Download } from "lucide-react";
import type { MouseEvent } from "react";
import { useOutputs, type Session } from "@/api/queries";
import { Card, CardTitle } from "@/components/ui/Card";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { formatBytes } from "@/lib/bytes";
import { eventReason, sessionState } from "@/lib/labels";

/** Its complete checkpoints, newest first: "Checkpoint 3 · 2 min ago". */
export function Checkpoints({ session }: { session: Session }) {
  const checkpoints = [...session.checkpoints].reverse();
  return (
    <Card aria-labelledby="checkpoints-title">
      <CardTitle id="checkpoints-title">Checkpoints</CardTitle>
      {checkpoints.length === 0 ? (
        <p className="text-small text-text-muted">
          None yet. A checkpoint lets the session carry on from there if its host drops.
        </p>
      ) : (
        <ul className="flex flex-col gap-2 text-small">
          {checkpoints.map((checkpoint) => (
            <li key={`${checkpoint.attempt}-${checkpoint.seq}`} className="flex flex-wrap gap-x-2">
              <span className="font-medium">Checkpoint {checkpoint.seq}</span>
              {session.attempt > 1 && <span className="text-text-muted">attempt {checkpoint.attempt}</span>}
              <span aria-hidden="true" className="text-text-muted">
                ·
              </span>
              <RelativeTime value={checkpoint.completedAt} className="text-text-muted" />
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** What happened, oldest first, in words; things that happened at the same moment are one entry. */
export function Timeline({ session }: { session: Session }) {
  const entries: { at: string; state: string; reasons: string[] }[] = [];
  for (const event of session.events) {
    const previous = entries.at(-1);
    if (previous?.at === event.at) {
      previous.state = event.state;
      previous.reasons.push(eventReason(event.reason));
    } else {
      entries.push({ at: event.at, state: event.state, reasons: [eventReason(event.reason)] });
    }
  }
  return (
    <Card aria-labelledby="timeline-title">
      <CardTitle id="timeline-title">Timeline</CardTitle>
      <ol className="flex flex-col">
        {entries.map((entry) => (
          <li key={entry.at} className="flex gap-4 pb-4 last:pb-0">
            <span aria-hidden="true" className="mt-2 size-2 shrink-0 rounded-full bg-border-strong" />
            <div className="min-w-0 flex-1">
              <p className="text-small font-medium">{sessionState(entry.state).label}</p>
              <p className="text-small text-text-muted">{entry.reasons.join(", then ")}</p>
            </div>
            <RelativeTime value={entry.at} className="text-caption text-text-muted tabular" />
          </li>
        ))}
      </ol>
    </Card>
  );
}

/** How long a download link lasts (ADR 0016), less a minute in hand. */
const LINK_LIFE_MS = 14 * 60_000;
const expired = (fetchedAt: number) => Date.now() - fetchedAt >= LINK_LIFE_MS;

/** A completed session's outputs, each with its size and a download; a link older than its life is renewed first. */
export function Outputs({ id }: { id: string }) {
  const outputs = useOutputs(id, true);

  const open = async (event: MouseEvent<HTMLAnchorElement>, path: string) => {
    if (!expired(outputs.dataUpdatedAt)) {
      return;
    }
    event.preventDefault();
    const fresh = await outputs.refetch();
    const url = fresh.data?.files.find((file) => file.path === path)?.url;
    if (url) {
      const link = document.createElement("a");
      link.href = url;
      link.download = path.split("/").pop() ?? path;
      link.click();
    }
  };

  return (
    <Card aria-labelledby="outputs-title" id="outputs">
      <CardTitle id="outputs-title">Outputs</CardTitle>
      {outputs.data ? (
        outputs.data.files.length === 0 ? (
          <EmptyState title="No outputs">This session finished without saving any outputs.</EmptyState>
        ) : (
          <>
            <ul className="divide-y divide-border rounded-control border border-border">
              {outputs.data.files.map((file) => (
                <li key={file.path}>
                  <a
                    href={file.url}
                    download
                    onClick={(event) => void open(event, file.path)}
                    className="flex min-h-touch items-center gap-3 px-3 py-2 text-small hover:bg-sunken"
                  >
                    <span className="min-w-0 flex-1 font-medium break-all">{file.path}</span>
                    <span className="text-caption text-text-muted tabular">
                      {formatBytes(file.sizeBytes)}
                    </span>
                    <Download
                      aria-hidden="true"
                      className="size-5 shrink-0 text-accent-text"
                      strokeWidth={1.75}
                    />
                  </a>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-caption text-text-muted">Outputs are kept for 30 days.</p>
          </>
        )
      ) : outputs.isError ? (
        <ErrorState
          error={outputs.error}
          what="the outputs"
          onRetry={() => void outputs.refetch()}
          retrying={outputs.isFetching}
        />
      ) : (
        <div role="status" aria-label="Loading the outputs">
          <Skeleton className="h-touch w-full" />
        </div>
      )}
    </Card>
  );
}
