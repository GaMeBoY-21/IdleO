import { useUsage } from "@/api/home";
import { Bars, BarsSkeleton } from "@/components/ui/Bars";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { Skeleton } from "@/components/ui/Skeleton";
import { formatMinutes } from "@/lib/hosting";
import { formatPaise } from "@/lib/money";

const dayOfMonth = (date: string) => Number(date.slice(8, 10));
const spokenDay = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "long", timeZone: "UTC" });

/**
 * This month's use (ADR 0058): what the renter spent and for how many minutes, each day so far as a small bar, all the
 * server's (`GET /v1/usage`). Asked for once the screen has painted; its skeleton is the same size. A month with nothing
 * yet says so, and where to start.
 */
export function UsagePanel({ enabled }: { enabled: boolean }) {
  const usage = useUsage(enabled);
  const month = usage.data;
  return (
    <Tile size="lg" aria-labelledby="usage-title" className="flex flex-col gap-2">
      <h2 id="usage-title" className="text-caption font-semibold text-ink-subtle">
        This month
      </h2>
      <div className="flex min-h-14 flex-col justify-center">
        {usage.isError ? (
          <p className="text-small text-ink-subtle">
            Couldn&apos;t load this month&apos;s use.{" "}
            <button type="button" className="font-semibold underline" onClick={() => void usage.refetch()}>
              Try again
            </button>
          </p>
        ) : month ? (
          <p className="flex flex-wrap items-baseline gap-x-2 tabular">
            <span className="text-hero">
              <Money paise={month.spentPaise} />
            </span>
            <span className="text-small text-ink-subtle">{formatMinutes(month.minutes)} of GPU time</span>
          </p>
        ) : (
          <Skeleton className="h-12 w-40" />
        )}
      </div>
      {month && month.spentPaise === 0 ? (
        // As tall as the bars, so a month with nothing yet moves nothing.
        <p className="flex h-18 items-center text-small text-ink-subtle">
          Nothing yet this month. A first session shows here, a bar a day.
        </p>
      ) : month ? (
        <Bars
          label="Spent each day this month"
          bars={month.days.map((day) => ({
            key: day.date,
            value: day.spentPaise,
            // Every fifth day and today are named under their bar; the rest are a screen reader's only.
            label:
              dayOfMonth(day.date) % 5 === 0 || day === month.days.at(-1) ? String(dayOfMonth(day.date)) : "",
            spoken: `${spokenDay.format(new Date(`${day.date}T00:00:00Z`))}, ${formatPaise(day.spentPaise)}`,
          }))}
        />
      ) : (
        <div className="h-18">{!usage.isError && <BarsSkeleton count={30} />}</div>
      )}
    </Tile>
  );
}
