import { Cpu } from "lucide-react";
import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { ProgressBar } from "@/components/ui/Progress";
import { Money } from "@/components/ui/Money";
import { StateChip } from "@/components/ui/StateChip";
import { Tile, TileLabel } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { gigabytes, gpuName } from "@/lib/gpu";
import { costSoFar } from "@/lib/sessions";

/**
 * A running session's dark tile (web/DESIGN.md "The session screen"): its state, what it's doing on which GPU, the big
 * timer ticking with "of about 45 min" when the template's typical runtime is known, the cost so far in lime against
 * the spending cap with a bar, and the price an hour. Money changes in place and never counts up. Its status has a
 * line of its own, always there, so nothing below moves as it changes.
 */
export function RunningTile({
  session,
  title,
  templateName,
  about,
  status,
  laptop,
}: {
  session: Session;
  title: string;
  templateName: string;
  about: string | null;
  status: ReactNode;
  laptop: boolean;
}) {
  const cost = costSoFar(session);
  const percent = Math.min(100, Math.floor((cost * 100) / Math.max(1, session.maxSpendPaise)));
  const price = session.pricePaisePerMin == null ? null : <Money paise={session.pricePaisePerMin * 60} />;
  return (
    <Tile tone="night" className="flex flex-col gap-5">
      <div className="flex flex-col gap-2">
        <span className="self-start">
          <StateChip state={session.state} />
        </span>
        <h1 className="text-title break-words">{title}</h1>
        <p className="text-small text-on-night-2">
          {session.startedAt ? (
            <RelativeTime value={session.startedAt} prefix="Started" />
          ) : (
            <RelativeTime value={session.createdAt} prefix="Booked" />
          )}
          {laptop && ` · ${templateName}`}
        </p>
        <p className="min-h-5 text-small text-on-night-2" aria-live="polite">
          {status}
        </p>
      </div>
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div className="flex flex-col gap-1">
          <p className={laptop ? "text-timer-xl" : "text-timer"}>
            <span className="sr-only">Running for </span>
            {session.startedAt ? <Elapsed from={session.startedAt} /> : <span className="tabular">0:00</span>}
          </p>
          {/* Always there, so the figures under it never move. */}
          <p className="min-h-5 text-small text-on-night-2">{about && `of ${about}`}</p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <Money paise={cost} className="text-figure text-volt" />
          <span className="min-h-5 text-small text-on-night-2">so far</span>
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <ProgressBar percent={percent} label="Cost so far against your spending cap" onDark />
        <p className="text-caption text-on-night-2 tabular">
          Stops at <Money paise={session.maxSpendPaise} />
          {price && <> · {price} an hour</>}
          {laptop && " · charged per started minute"}
        </p>
      </div>
    </Tile>
  );
}

/**
 * The GPU it's on, with how busy and how hot it is (§0.4 of the API: from its host's heartbeats, or unknown when
 * those are stale). Never the host's identity.
 */
export function GpuTile({ gpu }: { gpu: NonNullable<Session["gpu"]> }) {
  const reading =
    gpu.utilPct == null || gpu.tempC == null ? "Reading its use…" : `${gpu.utilPct}% busy · ${gpu.tempC}°C`;
  return (
    <Tile className="flex flex-col gap-1">
      <TileLabel className="flex items-center gap-1.5">
        <Cpu aria-hidden="true" className="size-4" strokeWidth={1.75} />
        GPU
      </TileLabel>
      <span className="text-option font-semibold break-words">
        {gpuName(gpu.model)} · {gigabytes(gpu.vramMb)}
      </span>
      <span className="text-small text-text-2 tabular">{reading}</span>
    </Tile>
  );
}

/** The running screen's small tiles side by side: the GPU, and the checkpoints beside it. */
export function SmallTiles({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-2 gap-2.5 md:gap-3.5">{children}</div>;
}
