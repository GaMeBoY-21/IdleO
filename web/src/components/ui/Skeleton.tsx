import { cn } from "@/lib/cn";

/**
 * A placeholder with the shape of what's loading (web/DESIGN.md "States"). Hidden from screen readers; the region
 * around it announces itself.
 */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn("skeleton rounded-control", className)} />;
}

/** Each text size's line height, and a bar a little shorter than it, so a line of skeleton is exactly as tall. */
const lines = {
  caption: ["h-4", "h-3"],
  small: ["h-5", "h-3"],
  body: ["h-6", "h-4"],
  title: ["h-8", "h-5"],
  heading: ["h-8", "h-6"],
  display: ["h-10", "h-8"],
} as const;

/**
 * One line of text that hasn't arrived: the line's own height, so nothing moves when the text replaces it. Give
 * `md` when the text changes size at 768 px, as the hero figure does.
 */
export function SkeletonLine({
  size,
  md,
  width,
}: {
  size: keyof typeof lines;
  md?: keyof typeof lines;
  width: string;
}) {
  const [line, bar] = lines[size];
  return (
    <div aria-hidden="true" className={cn("flex items-center", line, md && mdLines[md][0])}>
      <div className={cn("skeleton rounded-control", bar, md && mdLines[md][1], width)} />
    </div>
  );
}

const mdLines = {
  caption: ["md:h-4", "md:h-3"],
  small: ["md:h-5", "md:h-3"],
  body: ["md:h-6", "md:h-4"],
  title: ["md:h-8", "md:h-5"],
  heading: ["md:h-8", "md:h-6"],
  display: ["md:h-10", "md:h-8"],
} as const;
