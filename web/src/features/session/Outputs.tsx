import { Check, Download } from "lucide-react";
import type { MouseEvent } from "react";
import { useOutputs, type Session } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { SectionTitle, Tile, TileLabel } from "@/components/ui/Tile";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { formatBytes } from "@/lib/bytes";
import { cn } from "@/lib/cn";

/**
 * "Last checkpoint saved 2 min ago": when its newest complete checkpoint was saved, in a tile of its own, one line that's
 * always there, so one arriving moves nothing. The line doesn't count them: a number that grows a digit would move
 * the time after it.
 */
export function CheckpointTile({ session }: { session: Session }) {
  const last = session.checkpoints.at(-1);
  return (
    <Tile className="flex flex-col gap-1">
      <TileLabel>Checkpoints</TileLabel>
      <p className="flex items-start gap-2 text-small">
        <Check
          aria-hidden="true"
          className={cn("mt-0.5 size-4 shrink-0", last ? "text-running" : "text-text-2")}
          strokeWidth={2.5}
        />
        {last ? (
          <span className="min-w-0 flex-1 font-semibold">
            Last checkpoint saved <RelativeTime value={last.completedAt} />
          </span>
        ) : (
          <span className="min-w-0 flex-1 text-text-2">No checkpoint saved yet</span>
        )}
      </p>
    </Tile>
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
  /** Every file, one after another (browsers refuse a burst), each from a link renewed when it is older than its life. */
  const all = async () => {
    const fresh =
      Date.now() - outputs.dataUpdatedAt < LINK_LIFE_MS ? outputs.data : (await outputs.refetch()).data;
    for (const file of fresh?.files ?? []) {
      const link = document.createElement("a");
      link.href = file.url;
      link.download = file.path.split("/").pop() ?? file.path;
      link.click();
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  };
  return { outputs, open, all };
}

/**
 * The done screen's one primary action, at the top of the volt outputs tile (so in the page's dark with volt text): "Download outputs", the file itself, when
 * there's one; "Download all", every file in turn, when there are several (each is also a row under it).
 */
export function DownloadOutputs({ id }: { id: string }) {
  const { outputs, open, all } = useFreshOutputs(id);
  const files = outputs.data?.files ?? [];
  const only = files.length === 1 ? files[0] : undefined;
  if (files.length === 0) {
    return null;
  }
  return only ? (
    <a
      href={only.url}
      download
      onClick={(event) => void open(event, only.path)}
      className={buttonClasses("ink", true, true)}
    >
      <Download aria-hidden="true" className="size-5" strokeWidth={1.75} />
      Download outputs
    </a>
  ) : (
    <button type="button" onClick={() => void all()} className={buttonClasses("ink", true, true)}>
      <Download aria-hidden="true" className="size-5" strokeWidth={1.75} />
      Download all
    </button>
  );
}

/** The outputs, as the lime tile: a row each with its size and a download, dividers between them. */
export function Outputs({ id }: { id: string }) {
  const { outputs, open } = useFreshOutputs(id);
  return (
    <Tile
      tone="volt"
      as="section"
      aria-labelledby="outputs-title"
      id="outputs"
      className="flex flex-col gap-1 xl:h-outputs"
    >
      <SectionTitle id="outputs-title">Outputs</SectionTitle>
      <DownloadOutputs id={id} />
      {outputs.data ? (
        outputs.data.files.length === 0 ? (
          <EmptyState title="No outputs">This session finished without saving any outputs.</EmptyState>
        ) : (
          <>
            <ul className="flex min-h-0 flex-col divide-y divide-ink/15 xl:overflow-y-auto">
              {outputs.data.files.map((file) => (
                <li key={file.path}>
                  <a
                    href={file.url}
                    download
                    onClick={(event) => void open(event, file.path)}
                    className="flex min-h-14 items-center gap-3 py-2 hover:underline"
                  >
                    <span className="min-w-0 flex-1 text-body font-semibold break-all">{file.path}</span>
                    <span className="text-small text-ink tabular">{formatBytes(file.sizeBytes)}</span>
                    <Download aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
                  </a>
                </li>
              ))}
            </ul>
            <p className="text-caption text-ink">Outputs are kept for 30 days.</p>
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
    </Tile>
  );
}
