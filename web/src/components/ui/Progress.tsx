import { cn } from "@/lib/cn";

/** How far something has got, as a thin bar; the figure beside it says the same in words. */
export function ProgressBar({
  percent,
  label,
  onDark = false,
}: {
  percent: number;
  label: string;
  /** On a night tile: its own line for a track and a volt bar. */
  onDark?: boolean;
}) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className={cn("h-2.5 w-full overflow-hidden rounded-full", onDark ? "bg-night-line" : "bg-line")}
    >
      <div
        className={cn("h-full rounded-full", onDark ? "bg-volt" : "bg-progress")}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
