import { Check, Download } from "lucide-react";
import type { MouseEvent } from "react";
import { useOutputs, type Session } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { SectionTitle } from "@/components/ui/Tile";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";

/**
 * "Last checkpoint saved 2 min ago": when its newest complete checkpoint was saved, in one line that's always there,
 * so one arriving moves nothing. The line doesn't count them: a number that grows a digit, or changes width in
 * Inter's proportional figures, would move the time after it.
 */
export function LastCheckpoint({ session, className }: { session: Session; className?: string }) {
  const last = session.checkpoints.at(-1);
  return (
    <p className={cn("flex items-center gap-3 text-body", className)}>
      <Check
        aria-hidden="true"
        className={cn("size-5 shrink-0", last ? "text-running" : "text-text-muted")}
        strokeWidth={2}
      />
      {last ? (
        <span className="min-w-0 flex-1">
          Last checkpoint saved <RelativeTime value={last.completedAt} />
        </span>
      ) : (
        <span className="min-w-0 flex-1 text-text-muted">No checkpoint saved yet</span>
      )}
    </p>
  );
}

/** How long a download link lasts (ADR 0016), less a minute in hand. */
const LINK_LIFE_MS = 14 * 60_000;

/** A completed session's outputs, a link renewed first when it's older than its life. */
function useFreshOutputs(id: string) {
  const outputs = useOutputs(id, true);
  const open = async (event: MouseEvent<HTMLAnchorElement>, path: string) => {
    if (Date.now() - outputs.dataUpdatedAt < LINK_LIFE_MS) {
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
  return { outputs, open };
}

/**
 * "Download outputs", the done screen's one primary action: the file itself when there's one, the list of them
 * when there are several.
 */
export function DownloadOutputs({ id }: { id: string }) {
  const { outputs, open } = useFreshOutputs(id);
  const files = outputs.data?.files ?? [];
  const only = files.length === 1 ? files[0] : undefined;
  return only ? (
    <a
      href={only.url}
      download
      onClick={(event) => void open(event, only.path)}
      className={buttonClasses("primary", true, true)}
    >
      <Download aria-hidden="true" className="size-5" strokeWidth={1.75} />
      Download outputs
    </a>
  ) : (
    <a href="#outputs" className={buttonClasses("primary", true, true)}>
      Download outputs
    </a>
  );
}

/** The outputs, a row each with its size and a download, dividers between them. */
export function Outputs({ id }: { id: string }) {
  const { outputs, open } = useFreshOutputs(id);
  return (
    <section aria-labelledby="outputs-title" id="outputs" className="flex flex-col gap-1">
      <SectionTitle id="outputs-title">Outputs</SectionTitle>
      {outputs.data ? (
        outputs.data.files.length === 0 ? (
          <EmptyState title="No outputs">This session finished without saving any outputs.</EmptyState>
        ) : (
          <>
            <ul className="flex flex-col divide-y divide-divider">
              {outputs.data.files.map((file) => (
                <li key={file.path}>
                  <a
                    href={file.url}
                    download
                    onClick={(event) => void open(event, file.path)}
                    className="flex min-h-14 items-center gap-3 py-2 hover:bg-fill"
                  >
                    <span className="min-w-0 flex-1 text-body font-semibold break-all">{file.path}</span>
                    <span className="text-small text-text-muted tabular">{formatBytes(file.sizeBytes)}</span>
                    <Download aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
                  </a>
                </li>
              ))}
            </ul>
            <p className="text-caption text-text-muted">Outputs are kept for 30 days.</p>
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
          <Skeleton className="h-14 w-full" />
        </div>
      )}
    </section>
  );
}
