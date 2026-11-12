import { cn } from "@/lib/cn";
import { barPercent } from "@/lib/hosting";

export interface Bar {
  key: string;
  value: number;
  /** Under the bar, shown. */
  label: string;
  /** What a screen reader hears for it: its whole name and figure. */
  spoken: string;
}

/**
 * Small bars, one a period (ADR 0058: a renter's days this month, a host's days and weeks): each as tall as its share of
 * the biggest, the last (now) in full, an empty one a thin line. A list, each item with its spoken name and figure.
 * `night` is the dark tile's colours, `panel` a white tile's.
 */
export function Bars({
  bars,
  label,
  tone,
  tall = false,
}: {
  bars: Bar[];
  label: string;
  tone: "night" | "panel";
  tall?: boolean;
}) {
  const biggest = Math.max(0, ...bars.map((bar) => bar.value));
  const night = tone === "night";
  return (
    <ul
      aria-label={label}
      // The gaps narrow as the bars grow many, so any number of them fits their panel.
      className={cn(
        "flex items-end",
        tall ? "h-chart" : "h-18",
        bars.length > 40 ? "gap-px" : bars.length > 20 ? "gap-1" : "gap-2",
      )}
    >
      {bars.map((bar, index) => {
        const percent = barPercent(bar.value, biggest);
        const now = index === bars.length - 1;
        return (
          <li key={bar.key} className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1">
            <span className="sr-only">{bar.spoken}</span>
            <span
              aria-hidden="true"
              className={cn(
                "w-full",
                tall ? "rounded-icon" : "rounded-control",
                night
                  ? tall
                    ? bar.value > 0
                      ? now
                        ? "bg-running-dot"
                        : "bg-on-night/20"
                      : "bg-on-night/10"
                    : bar.value > 0
                      ? now
                        ? "bg-running-dot"
                        : "bg-running-dot/50"
                      : "bg-on-night/20"
                  : bar.value > 0
                    ? now
                      ? "bg-progress"
                      : "bg-progress/50"
                    : "bg-line",
              )}
              style={{ height: percent === 0 ? undefined : `${percent}%`, minHeight: "0.25rem" }}
            />
            <span
              aria-hidden="true"
              className={cn(
                "text-caption",
                night ? (now ? "font-bold text-volt" : "text-on-night-2") : now ? "font-bold" : "text-text-2",
              )}
            >
              {bar.label}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** The bars' shape before the figures arrive: the same box. */
export function BarsSkeleton({ count, tone }: { count: number; tone: "night" | "panel" }) {
  return (
    <div aria-hidden="true" className="flex h-18 items-end gap-2">
      {Array.from({ length: count }, (_, key) => (
        <span
          key={key}
          className={cn("h-full flex-1 rounded-control", tone === "night" ? "skeleton-dark" : "skeleton")}
        />
      ))}
    </div>
  );
}
