import type { ReactNode } from "react";
import { Plus } from "lucide-react";
import type { Host, OwnerEarnings } from "@/api/hosting";
import { FIT_FRAME, GRID } from "@/components/shell/Layout";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile, TileLink } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { DemandTile, EarningsHero, HelpTile, RecentPayoutsTile, ScoreTile } from "./HomeCards";
import { MachineTile } from "./MachineTile";
import { HOST_PATHS } from "@/sites/host/paths";

/** The apricot tile: what a machine needs, and that a one-time code links it. */
export function AddTile({ className }: { className?: string }) {
  return (
    <TileLink href={HOST_PATHS.add} tone="ember" size="lg" className={cn("gap-4 xl:p-7", className)}>
      <span aria-hidden="true" className="flex size-14 items-center justify-center rounded-icon bg-ink/10">
        <Plus className="size-6" strokeWidth={2} />
      </span>
      <span className="flex flex-col gap-3">
        <ul className="flex flex-col gap-1 text-small text-ink">
          <li>Windows 10 or 11</li>
          <li>An NVIDIA RTX graphics card</li>
          <li>Plugged in, with an internet connection that stays on</li>
        </ul>
        <span className="flex flex-col gap-1.5">
          <span className="font-title text-display-card">Add a machine</span>
          <span className="text-body text-ink">
            A one-time code links your laptop. Takes about 5 minutes.
          </span>
        </span>
      </span>
    </TileLink>
  );
}

/** With no machine yet: what hosting is, in the dark tile, and "Add a machine" beside it. */
export function LaptopPitch() {
  return (
    <div className={cn(FIT_FRAME, GRID)}>
      <Tile
        tone="night"
        size="lg"
        aria-labelledby="pitch-title"
        className="flex flex-col justify-between gap-6 md:col-span-6 xl:col-span-8 xl:min-h-96 xl:p-8"
      >
        <h1 id="pitch-title" className="font-title text-display-panel">
          Earn from the GPU you aren&apos;t using
        </h1>
        <p className="max-w-prose text-body text-on-night-2">
          Add a gaming laptop or desktop with an NVIDIA RTX GPU. Renters&apos; jobs run in a locked box, never
          in your files, and you set the price. Pause or take it back whenever you like.
        </p>
      </Tile>
      <AddTile className="md:col-span-6 xl:col-span-4" />
    </div>
  );
}

/**
 * The host site's home from 768 px (web/DESIGN.md "Laptop layout", ADR 0058's "full and simple"): the earnings in the
 * dark tile (this week, pending, the next payout, 8 weeks of bars) and Add a laptop beside it; each laptop as a tile;
 * then the demand for its GPU, its score and the last payouts; and a new host's checklist beside Help. Every figure is
 * the server's; what isn't the first paint's is asked for once the screen has painted.
 */
export function LaptopHosting({
  machines,
  owner,
  enabled,
  restoredAt,
  checklist,
}: {
  machines: Host[] | undefined;
  owner: OwnerEarnings | undefined;
  enabled: boolean;
  /** When figures restored from an earlier visit are from, until fresh ones arrive. */
  restoredAt: number | null;
  /** The first run's checklist (FirstRun), drawn beside Help while it has steps left. */
  checklist: ReactNode;
}) {
  const first = machines?.[0];
  return (
    <div className={cn(FIT_FRAME, GRID, "xl:auto-rows-auto")}>
      <h1 className="sr-only">Hosting</h1>
      <EarningsHero owner={owner} enabled={enabled} restoredAt={restoredAt} />
      <AddTile className="md:col-span-6 xl:col-span-4" />
      {machines === undefined ? (
        <>
          <span role="status" aria-label="Loading your machines" className="sr-only" />
          <Skeleton tile className="h-service-card md:col-span-3 xl:col-span-4 xl:h-host-panel" />
          <Skeleton tile className="h-service-card md:col-span-3 xl:col-span-4 xl:h-host-panel" />
        </>
      ) : (
        machines.map((host) => (
          <MachineTile key={host.id} host={host} className="md:col-span-3 xl:col-span-4 xl:h-host-panel" />
        ))
      )}
      <DemandTile host={first} enabled={enabled} />
      <ScoreTile host={first} />
      <RecentPayoutsTile enabled={enabled} />
      <div className="md:col-span-6 xl:col-span-8">{checklist}</div>
      <HelpTile className="md:col-span-6 xl:col-span-4" />
    </div>
  );
}
