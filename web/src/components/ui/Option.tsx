import { Check, type LucideIcon } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * One choice among a few (web/DESIGN.md "Shapes and space"): an icon square, a title and a line under it. It is a
 * white tile on the page and a tile pressed into the page's colour inside another tile; the one that is selected is
 * lime, with a check.
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
        "flex w-full items-center gap-4 rounded-tile px-4.5 py-3.5 text-left tiled:rounded-control transition-colors",
        selected
          ? "bg-lime text-on-colour"
          : "bg-fill hover:bg-fill-selected tiled:bg-ground tiled:hover:bg-divider",
        className,
      )}
      {...props}
    >
      {Icon && <IconTile icon={Icon} strong={selected ?? false} />}
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-option font-semibold break-words">{title}</span>
        {detail && (
          <span className={cn("text-small", selected ? "text-lime-fg" : "text-text-muted")}>{detail}</span>
        )}
      </span>
      {selected ? <Check aria-hidden="true" className="size-5.5 shrink-0" strokeWidth={2.5} /> : trailing}
    </button>
  );
}

/** A square for an icon: the page's colour (a tile's, inside one), or dark when it stands for the choice that's made. */
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
        strong ? "bg-primary text-primary-fg" : "bg-ground text-text tiled:bg-fill",
      )}
    >
      <Icon className={size === "lg" ? "size-7" : "size-5"} strokeWidth={1.75} />
    </span>
  );
}
