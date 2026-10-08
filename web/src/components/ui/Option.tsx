import { ChevronRight, type LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * One choice among a few (web/DESIGN.md "Shapes"): an icon tile, a title and a line under it. The selected one has a
 * 2 px border in the text colour on a light fill, and its tile turns black (white in the dark theme).
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
        "flex w-full items-center gap-4 rounded-control border-2 px-4 py-3.5 text-left",
        selected ? "border-text bg-fill-selected" : "border-transparent hover:bg-fill",
        className,
      )}
      {...props}
    >
      {Icon && <IconTile icon={Icon} strong={selected ?? false} />}
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-option font-semibold break-words">{title}</span>
        {detail && <span className="text-small text-text-muted">{detail}</span>}
      </span>
      {trailing}
    </button>
  );
}

/** A square for an icon: grey, or black when it stands for the choice that's made. */
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
        "flex shrink-0 items-center justify-center rounded-icon",
        size === "lg" ? "size-12" : "size-touch",
        strong ? "bg-primary text-primary-fg" : "bg-fill text-text",
      )}
    >
      <Icon className={size === "lg" ? "size-7" : "size-5"} strokeWidth={1.75} />
    </span>
  );
}

/** A grey row that opens something: Settings, Logs. */
export function RowButton({
  icon: Icon,
  title,
  detail,
  className,
  ...props
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "title"> & {
  icon: LucideIcon;
  title: ReactNode;
  detail?: ReactNode;
}) {
  return (
    <button
      type="button"
      className={cn(
        "flex w-full items-center gap-3.5 rounded-control bg-fill px-4 py-3.5 text-left hover:bg-fill-selected",
        className,
      )}
      {...props}
    >
      <Icon aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-body font-semibold">{title}</span>
        {detail && <span className="text-small text-text-muted tabular">{detail}</span>}
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </button>
  );
}
