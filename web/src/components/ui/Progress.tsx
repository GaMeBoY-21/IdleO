import { cn } from "@/lib/cn";

/** How far something has got, as a thin bar; the figure beside it says the same in words. */
export function ProgressBar({
  percent,
  label,
  className,
}: {
  percent: number;
  label: string;
  className?: string;
}) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className={cn("h-1.5 w-full overflow-hidden rounded-full bg-surface-3", className)}
    >
      <div className="h-full rounded-full bg-live" style={{ width: `${percent}%` }} />
    </div>
  );
}
