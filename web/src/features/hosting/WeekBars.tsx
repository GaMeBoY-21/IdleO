import type { components } from "@/api/schema.gen";
import { cn } from "@/lib/cn";
import { barPercent } from "@/lib/hosting";
import { formatPaise } from "@/lib/money";

type Day = components["schemas"]["EarningsDay"];

const weekday = new Intl.DateTimeFormat("en-IN", { weekday: "long", timeZone: "UTC" });

/** The day's date is a calendar date in India; read as UTC midnight it names the same weekday everywhere. */
const dayName = (date: string) => weekday.format(new Date(`${date}T00:00:00Z`));

/**
 * The last 7 days as bars on the dark tile: each as tall as its share of the busiest day, in lime, today in full, a
 * day with nothing a thin line. Each day is a list item with its name and amount for a screen reader.
 */
export function WeekBars({ days }: { days: Day[] }) {
  const busiest = Math.max(0, ...days.map((day) => day.earnedPaise));
  return (
    <ul aria-label="Earned each day, the last 7 days" className="flex h-18 items-end gap-2">
      {days.map((day, index) => {
        const percent = barPercent(day.earnedPaise, busiest);
        const today = index === days.length - 1;
        return (
          <li key={day.date} className="flex h-full flex-1 flex-col items-center justify-end gap-1.5">
            <span className="sr-only">
              {dayName(day.date)}, {formatPaise(day.earnedPaise)}
            </span>
            <span
              aria-hidden="true"
              className={cn(
                "w-full rounded-control",
                day.earnedPaise > 0 ? (today ? "bg-live" : "bg-live/50") : "bg-dark-fg/20",
              )}
              style={{ height: percent === 0 ? undefined : `${percent}%`, minHeight: "0.25rem" }}
            />
            <span
              aria-hidden="true"
              className={cn("text-caption", today ? "font-semibold" : "text-dark-muted")}
            >
              {dayName(day.date).charAt(0)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** The bars' shape before the figures arrive: the same box. */
export function WeekBarsSkeleton() {
  return (
    <div aria-hidden="true" className="flex h-18 items-end gap-2">
      {Array.from({ length: 7 }, (_, key) => (
        <span key={key} className="skeleton-dark h-full flex-1 rounded-control" />
      ))}
    </div>
  );
}
