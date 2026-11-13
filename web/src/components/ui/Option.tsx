import { Check, type LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * One choice among a few (web/DESIGN.md "Shapes and space"): an icon square, a title and a line under it, on a surface-1
 * card with a hairline. The one that is selected goes up the ladder with a stronger border and a check, never colour
 * alone.
 */
export function Option({
  icon: Icon,
  title,
  detail,
  selected,
  trailing,
  className,
  ...props
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "title"> & {
  icon?: LucideIcon;
  title: ReactNode;
  detail?: ReactNode;
  selected?: boolean;
  trailing?: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={cn(
        "flex w-full items-center gap-4 rounded-card border px-4 py-4 text-left transition-colors",
        selected
          ? "border-hairline-tertiary bg-surface-3 text-ink"
          : "border-hairline bg-surface-1 hover:bg-surface-2",
        className,
      )}
      {...props}
    >
      {Icon && <IconTile icon={Icon} strong={selected ?? false} />}
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-card break-words">{title}</span>
        {detail && <span className="text-small text-ink-subtle">{detail}</span>}
      </span>
      {selected ? <Check aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} /> : trailing}
    </button>
  );
}

/** A small square for an icon: surface-2, or one step up when it stands for the choice that's made. */
export function IconTile({
  icon: Icon,
  strong = false,
  size = "md",
}: {
  icon: LucideIcon;
  strong?: boolean;
  size?: "md" | "lg";
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex shrink-0 items-center justify-center rounded-control border border-hairline",
        size === "lg" ? "size-12" : "size-9",
        strong ? "bg-surface-4 text-ink" : "bg-surface-2 text-ink-muted",
      )}
    >
      <Icon className={size === "lg" ? "size-6" : "size-5"} strokeWidth={1.75} />
    </span>
  );
}
