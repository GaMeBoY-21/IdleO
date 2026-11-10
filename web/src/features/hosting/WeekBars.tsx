import { Bars, BarsSkeleton } from "@/components/ui/Bars";
import type { components } from "@/api/schema.gen";
import { formatPaise } from "@/lib/money";

type Day = components["schemas"]["EarningsDay"];
type Week = components["schemas"]["EarningsWeek"];

const weekday = new Intl.DateTimeFormat("en-IN", { weekday: "long", timeZone: "UTC" });

/** The day's date is a calendar date in India; read as UTC midnight it names the same weekday everywhere. */
const dayName = (date: string) => weekday.format(new Date(`${date}T00:00:00Z`));

/**
 * The last 7 days as bars on the dark tile: each as tall as its share of the busiest day, in lime, today in full, a
 * day with nothing a thin line. Each day is a list item with its name and amount for a screen reader.
 */
export function WeekBars({ days, tall = false }: { days: Day[]; tall?: boolean }) {
  return (
    <Bars
      label="Earned each day, the last 7 days"
      tone="night"
      tall={tall}
      bars={days.map((day) => ({
        key: day.date,
        value: day.earnedPaise,
        label: tall ? dayName(day.date).slice(0, 3) : dayName(day.date).charAt(0),
        spoken: `${dayName(day.date)}, ${formatPaise(day.earnedPaise)}`,
      }))}
    />
  );
}

/** The bars' shape before the figures arrive: the same box. */
export function WeekBarsSkeleton() {
  return <BarsSkeleton count={7} tone="night" />;
}

const weekOf = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "Asia/Kolkata" });

/** The last 8 weeks from Monday as bars (ADR 0058), this week last: each named by its Monday. */
export function WeeksBars({ weeks, tone = "night" }: { weeks: Week[]; tone?: "night" | "panel" }) {
  return (
    <Bars
      label="Earned each week, the last 8 weeks"
      tone={tone}
      bars={weeks.map((week) => ({
        key: week.startsAt,
        value: week.earnedPaise,
        label: weekOf.format(new Date(week.startsAt)),
        spoken: `The week from ${weekOf.format(new Date(week.startsAt))}, ${formatPaise(week.earnedPaise)}`,
      }))}
    />
  );
}
