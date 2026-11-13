import type { HTMLAttributes, ReactNode } from "react";
import { AppLink } from "./AppLink";
import { cn } from "@/lib/cn";

/**
 * Cards (web/DESIGN.md "Shapes and space", ADR 0060): a surface of the ladder with a hairline border and 12 px corners,
 * no shadow. A `panel` sits on the canvas as surface-1; `raised` is one step up (surface-2) for what matters now, or for a
 * card on a card. Colour is never a card's fill: the accent is only a button, a ring, the brand mark and live marks.
 * `feature` is a big panel with 16 px corners and the faint top-edge highlight of a lifted panel.
 */
export type TileTone = "panel" | "raised";

const surfaces: Record<TileTone, string> = {
  panel: "border border-hairline bg-surface-1 text-ink",
  raised: "border border-hairline bg-surface-2 text-ink",
};

/** The colour of a card's small lines (its label, the line under its title). */
export const tileMuted = "text-ink-subtle";

/**
 * `lg`: the laptop's padding, 24 px and 32 px from 1200 px (web/DESIGN.md "Laptop layout"). `feature`: a big panel with
 * 16 px corners, as the live session's band.
 */
export type TileSize = "md" | "lg" | "feature";

export function tileClasses(tone: TileTone = "panel", size: TileSize = "md") {
  return cn(
    size === "feature" ? "rounded-panel p-6 shadow-lift xl:px-8 xl:py-8" : "rounded-card",
    size === "lg" ? "p-6 xl:p-8" : size === "md" ? "p-4" : "",
    surfaces[tone],
  );
}

export function Tile({
  tone = "panel",
  size = "md",
  as: Tag = "section",
  className,
  ...props
}: HTMLAttributes<HTMLElement> & {
  tone?: TileTone;
  size?: TileSize;
  as?: "section" | "div" | "li" | "article";
}) {
  return <Tag className={cn(tileClasses(tone, size), className)} {...props} />;
}

/** A card that opens something: it goes up one step of the ladder on hover (`tile-motion`). */
export function TileLink({
  href,
  tone = "panel",
  size = "md",
  className,
  children,
  ...props
}: Omit<HTMLAttributes<HTMLAnchorElement>, "href"> & {
  href: string;
  tone?: TileTone;
  size?: TileSize;
  children: ReactNode;
}) {
  return (
    <AppLink
      href={href}
      className={cn(tileClasses(tone, size), "tile-motion flex flex-col justify-between", className)}
      {...props}
    >
      {children}
    </AppLink>
  );
}

/** A card's small label ("Credit", "Running now"). */
export function TileLabel({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return <span className={cn("text-caption", tileMuted, className)} {...props} />;
}
