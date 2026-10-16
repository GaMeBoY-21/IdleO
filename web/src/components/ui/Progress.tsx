import { cn } from "@/lib/cn";

/** How far something has got, as a thin bar; the figure beside it says the same in words. */
export function ProgressBar({
  percent,
  label,
  onDark = false,
}: {
  percent: number;
  label: string;
  /** On the dark tile: a faint track and a lime bar. */
  onDark?: boolean;
}) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className={cn("h-1.5 w-full overflow-hidden rounded-full", onDark ? "bg-dark-fg/15" : "bg-divider")}
    >
      <div
        className={cn("h-full rounded-full", onDark ? "bg-live" : "bg-primary")}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
