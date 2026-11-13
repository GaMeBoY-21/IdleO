import { useDemand, usePriceBand, type Host, type OwnerEarnings } from "@/api/hosting";
import { usePayoutHistory, usePayoutSummary } from "@/api/payouts";
import { StatGrid, StatTile } from "@/components/ui/KeyValues";
import { SectionHeader } from "@/components/ui/Headers";
import { AppLink } from "@/components/ui/AppLink";
import { Bars, BarsSkeleton } from "@/components/ui/Bars";
import { Money } from "@/components/ui/Money";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { formatMinutes, reliabilityRaisers, reliabilityWord } from "@/lib/hosting";
import { formatPaise, formatPaiseRange } from "@/lib/money";
import { siteLink } from "@/lib/site-links";
import { formatDay, formatTime } from "@/lib/time";
import { HELP_VIRTUALIZATION } from "@/site/paths";
import { HOST_PATHS } from "@/sites/host/paths";
import { PayoutRow } from "../Payouts";
import { WeeksBars } from "../WeekBars";

/**
 * The earnings on the host site's home (ADR 0058), the raised card: this week, what's pending, the next payout's day and
 * amount, and the last 8 weeks as bars. Every figure is the server's; the payouts' once the screen has painted.
 */
export function EarningsHero({
  owner,
  enabled,
  restoredAt,
}: {
  owner: OwnerEarnings | undefined;
  enabled: boolean;
  restoredAt: number | null;
}) {
  const summary = usePayoutSummary(enabled).data;
  const nextAmount =
    summary && summary.availablePaise >= summary.minimumPaise ? summary.availablePaise : null;
  return (
    <Tile
      tone="raised"
      size="lg"
      aria-labelledby="week-title"
      className="grid grid-cols-1 gap-6 md:col-span-6 md:grid-cols-2 xl:col-span-8 xl:h-host-panel xl:grid-cols-5 xl:gap-8 xl:py-8"
    >
      {/* Its figures at the foot, so nothing that arrives above them moves them. */}
      <div className="flex flex-col justify-between gap-4 xl:col-span-2">
        {/* Two lines kept, so the note on figures from an earlier visit coming and going moves nothing. */}
        <h2 id="week-title" className="min-h-9 text-caption text-ink-subtle">
          Earned this week, all laptops
          {restoredAt !== null && (
            <>
              {" "}
              · from <span className="tabular">{formatTime(restoredAt)}</span>, refreshing
            </>
          )}
        </h2>
        {/* The figure and its line, the same height before they arrive and after. */}
        <span className="flex min-h-24 flex-col gap-1">
          {owner ? (
            <>
              <Money paise={owner.weekPaise} className="block text-timer-lg" />
              <span className="truncate text-small text-ink-subtle tabular">
                <Money paise={owner.totalPaise} /> in total · {formatMinutes(owner.minutesRentedThisWeek)}{" "}
                rented
              </span>
            </>
          ) : (
            <Skeleton className="skeleton h-15 w-48" />
          )}
        </span>
        <StatGrid>
          {/* Small, with room for two lines, so a figure that wraps in the narrow panel moves nothing when it arrives. */}
          <StatTile label="Pending" size="sm">
            {summary ? <Money paise={summary.pendingPaise} /> : "…"}
          </StatTile>
          <StatTile label="Next payout" size="sm">
            {summary
              ? `${formatDay(summary.nextPayoutAt)}${nextAmount !== null ? ` · ${formatPaise(nextAmount)}` : ""}`
              : "…"}
          </StatTile>
        </StatGrid>
      </div>
      <div className="flex flex-col justify-end gap-2 xl:col-span-3">
        {owner ? <WeeksBars weeks={owner.weeks} /> : <BarsSkeleton count={8} />}
        <AppLink
          href={HOST_PATHS.earnings}
          className="self-start text-small text-ink underline underline-offset-4 hover:text-ink-muted"
        >
          Earnings, all laptops →
        </AppLink>
      </div>
    </Tile>
  );
}

const hourLabel = (hour: number) =>
  hour === 0 ? "12a" : hour === 12 ? "12p" : hour < 12 ? `${hour}` : `${hour - 12}`;

/**
 * "Demand for your GPU" (ADR 0058): when renters asked for a GPU this laptop could run, by hour in India over the last
 * 28 days, from the platform's own bookings and the ones that found no host; and what similar laptops ask, from its
 * price band. With too few bookings it says so rather than draw a pattern from noise.
 */
export function DemandTile({
  host,
  enabled,
  className = "md:col-span-6 xl:col-span-6 xl:h-host-panel",
}: {
  host: Host | undefined;
  enabled: boolean;
  /** Its place in the grid: the home's by default. */
  className?: string;
}) {
  const vram = host?.gpus[0]?.vramMb;
  const demand = useDemand(vram, enabled);
  const band = usePriceBand(host?.id ?? "", enabled && host !== undefined);
  const busiest = demand.data?.hours.reduce((top, hour) => (hour.asked > top.asked ? hour : top));
  return (
    <Tile as="section" aria-labelledby="demand-title" className={cn("flex flex-col gap-2 xl:p-8", className)}>
      <SectionHeader id="demand-title">Demand for your GPU</SectionHeader>
      {host === undefined ? (
        <p className="text-small text-ink-subtle">Link a laptop, and when renters want its GPU shows here.</p>
      ) : demand.data ? (
        <>
          <p className="text-small text-ink-subtle">
            {demand.data.enough && busiest
              ? `In the last ${demand.data.days} days renters asked for a GPU like this ${demand.data.asked} times, most around ${hourLabel(busiest.hour)}${busiest.hour < 12 ? " am" : " pm"}; ${demand.data.unmet} found no host free.`
              : `Too few bookings in the last ${demand.data.days} days to show a pattern yet (${demand.data.asked}).`}
          </p>
          <Bars
            label="Bookings a GPU like this could run, by hour of the day"
            bars={demand.data.hours.map((hour) => ({
              key: String(hour.hour),
              value: hour.asked,
              label: hour.hour % 6 === 0 ? hourLabel(hour.hour) : "",
              spoken: `${hour.hour}:00, ${hour.asked} asked, ${hour.unmet} found no host`,
            }))}
          />
          <p className="min-h-6 text-small">
            {band.data && band.data.sampleSize > 0
              ? `Similar laptops ask ${formatPaiseRange(band.data.lowPaisePerMin * 60, band.data.highPaisePerMin * 60)} an hour.`
              : "No similar laptops to compare a price with yet."}
          </p>
        </>
      ) : (
        <div role="status" aria-label="Loading demand" className="flex flex-col gap-2">
          <SkeletonLine size="small" width="w-3/4" />
          <BarsSkeleton count={24} />
          <SkeletonLine size="small" width="w-1/2" />
        </div>
      )}
    </Tile>
  );
}

/** The host score, plainly (ADR 0058): the first laptop's word for it, and what would raise it. */
export function ScoreTile({
  host,
  className = "md:col-span-3 xl:col-span-3 xl:h-host-panel",
}: {
  host: Host | undefined;
  /** Its place in the grid: the home's by default. */
  className?: string;
}) {
  return (
    <Tile as="section" aria-labelledby="score-title" className={cn("flex flex-col gap-2 xl:p-8", className)}>
      <SectionHeader id="score-title">Host score</SectionHeader>
      {host ? (
        <>
          <p className="text-section">{reliabilityWord(host.reliability.label)}</p>
          {host.rating && (
            <p className="text-small text-ink-subtle tabular">
              {host.rating.averageStars.toFixed(1)} stars from at least {host.rating.countAtLeast} renters
            </p>
          )}
          <ul className="flex flex-col gap-1 text-small">
            {reliabilityRaisers(host.reliability).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </>
      ) : (
        <p className="text-small text-ink-subtle">
          Each laptop earns its own score from the sessions it runs.
        </p>
      )}
    </Tile>
  );
}

/** The last payouts (ADR 0040), each with its TDS, and the way to all of them. */
export function RecentPayoutsTile({ enabled }: { enabled: boolean }) {
  const history = usePayoutHistory(enabled);
  const payouts = history.data?.pages[0]?.payouts.slice(0, 2);
  return (
    <Tile
      as="section"
      aria-labelledby="recent-payouts-title"
      className="flex flex-col gap-1 md:col-span-3 xl:col-span-3 xl:h-host-panel xl:p-8"
    >
      <SectionHeader
        id="recent-payouts-title"
        action={
          <AppLink href={HOST_PATHS.payouts} className="text-small font-semibold underline">
            All
          </AppLink>
        }
      >
        Payouts
      </SectionHeader>
      {payouts ? (
        payouts.length === 0 ? (
          <p className="text-small text-ink-subtle">
            None yet. Earnings are paid every Monday once enough is available.{" "}
            <AppLink href={HOST_PATHS.payoutDetails} className="font-semibold underline">
              Payout details
            </AppLink>
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-hairline">
            {payouts.map((payout) => (
              <PayoutRow key={payout.id} payout={payout} />
            ))}
          </ul>
        )
      ) : (
        <div role="status" aria-label="Loading your payouts" className="flex flex-col gap-2 py-2">
          <SkeletonLine size="card" width="w-3/4" />
          <SkeletonLine size="small" width="w-1/2" />
        </div>
      )}
    </Tile>
  );
}

/** Help, small (ADR 0058): the Windows app, what to do when something's wrong, and writing to us. */
export function HelpTile({ className }: { className?: string }) {
  const links = [
    { href: HOST_PATHS.add, label: "Download the Windows app" },
    { href: siteLink(`${HELP_VIRTUALIZATION}other/`), label: "The app says virtualization is off" },
    { href: HOST_PATHS.help, label: "All help" },
  ];
  return (
    <Tile as="section" aria-labelledby="help-title" className={cn("flex flex-col gap-1 xl:p-8", className)}>
      <SectionHeader id="help-title">Help</SectionHeader>
      <ul className="flex flex-col divide-y divide-hairline">
        {links.map((link) => (
          <li key={link.href}>
            <AppLink href={link.href} className="flex min-h-touch items-center text-body hover:underline">
              {link.label}
            </AppLink>
          </li>
        ))}
      </ul>
    </Tile>
  );
}
