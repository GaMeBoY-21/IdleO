import type { InfiniteData, UseInfiniteQueryResult } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import type { Host, OwnerEarnings } from "@/api/hosting";
import { FIT_FRAME, GRID } from "@/components/shell/Layout";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile, TileLink } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { formatMinutes } from "@/lib/hosting";
import { formatTime } from "@/lib/time";
import { WeekBars } from "../WeekBars";
import { LatestEarnings } from "./LatestEarnings";
import { MachineTile } from "./MachineTile";

type EarningsQuery = UseInfiniteQueryResult<InfiniteData<OwnerEarnings>>;

/** The apricot tile: what a machine needs, and that a one-time code links it. */
export function AddTile({ className }: { className?: string }) {
  return (
    <TileLink href="/hosting/add/" tone="ember" size="lg" className={cn("gap-4 xl:p-7", className)}>
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
          <span className="text-title">Add a machine</span>
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
        <h1 id="pitch-title" className="text-question">
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
 * The Hosting tab from 768 px (docs/design/bento/laptop/hosting-laptop.html): the week, in the dark tile split in two
 * (the amount, and the seven days as bars), and "Add a machine" beside it; under them each machine as a tile (up to
 * two beside the latest earnings; with more, a grid of three a row and the earnings under it). Two equal rows that fill
 * the screen from 1200 px. Every figure is the server's, the earnings read again while a machine runs a session.
 */
export function LaptopHosting({
  machines,
  owner,
  earnings,
  restoredAt,
}: {
  machines: Host[] | undefined;
  owner: OwnerEarnings | undefined;
  earnings: EarningsQuery;
  /** When figures restored from an earlier visit are from, until fresh ones arrive. */
  restoredAt: number | null;
}) {
  const latest = earnings.data?.pages[0]?.history.entries ?? [];
  const many = (machines?.length ?? 0) > 2;
  return (
    <div className={cn(FIT_FRAME, GRID, many ? "xl:auto-rows-auto" : "rows-even xl:fit-screen")}>
      <h1 className="sr-only">Hosting</h1>
      <Tile
        tone="night"
        size="lg"
        aria-labelledby="week-title"
        className="grid gap-6 md:col-span-6 md:grid-cols-2 xl:col-span-8 xl:grid-cols-5 xl:gap-8 xl:py-7"
      >
        <h2 id="week-title" className="sr-only">
          Your earnings
        </h2>
        <div className="flex flex-col justify-between gap-4 xl:col-span-2">
          <span className="text-caption text-on-night-2">
            Earned this week, all machines
            {restoredAt !== null && (
              <>
                {" "}
                · from <span className="tabular">{formatTime(restoredAt)}</span>, refreshing
              </>
            )}
          </span>
          {owner ? (
            <span className="flex flex-col gap-1">
              <Money paise={owner.weekPaise} className="block text-timer-lg" />
              <span className="text-small text-on-night-2 tabular">
                <Money paise={owner.totalPaise} /> in total · {formatMinutes(owner.minutesRentedThisWeek)}{" "}
                rented
              </span>
            </span>
          ) : (
            <Skeleton className="skeleton-dark h-16 w-48" />
          )}
          <span className="text-caption text-on-night-2">
            Earnings are recorded now. Weekly UPI payouts start with the paid pilot.
          </span>
        </div>
        <div className="flex flex-col justify-end xl:col-span-3">
          {owner ? <WeekBars days={owner.days} tall /> : <Skeleton className="skeleton-dark h-chart" />}
        </div>
      </Tile>
      <AddTile className="md:col-span-6 xl:col-span-4" />
      {machines === undefined ? (
        <>
          <span role="status" aria-label="Loading your machines" className="sr-only" />
          <Skeleton tile className="md:col-span-3 xl:col-span-4" />
          <Skeleton tile className="md:col-span-3 xl:col-span-4" />
          <Skeleton tile className="md:col-span-6 xl:col-span-4" />
        </>
      ) : many ? (
        <>
          <div className="grid gap-4 md:col-span-6 md:grid-cols-2 xl:col-span-12 xl:grid-cols-3 2xl:gap-5">
            {machines.map((host) => (
              <MachineTile key={host.id} host={host} />
            ))}
          </div>
          <LatestEarnings entries={latest} className="md:col-span-6 xl:col-span-12" />
        </>
      ) : (
        <>
          {machines.map((host) => (
            <MachineTile key={host.id} host={host} className="md:col-span-3 xl:col-span-4" />
          ))}
          <LatestEarnings entries={latest} className="md:col-span-6 xl:col-span-4" />
        </>
      )}
    </div>
  );
}
