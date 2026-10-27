import type { HTMLAttributes, ReactNode } from "react";
import { AppLink } from "./AppLink";
import { cn } from "@/lib/cn";

/**
 * Tiles (web/DESIGN.md "Shapes and space", ADR 0043): the one surface of Night Shift. A `panel` sits on the page; a
 * `night` tile stays dark in Day Shift too, and is what matters now; `volt`, `ion` and `ember` are the main action,
 * history, and hosting and earning; `ion-deep` is the credit tile. Every tile has 28 px corners and 18 px of padding,
 * and depth comes from colour: a panel has a 1 px edge by day and none at night. Anything inside a tile that is filled (a
 * field, a ghost button) is pressed in with a darker or lighter step of the tile's own colour: a tile marks its inside
 * with `data-tile`, which the `tiled` variant (styles.css) reads, so those components never need to know where they
 * are. A `night` tile also carries the dark theme's colours for everything inside it (tokens.gen.css).
 */
export type TileTone = "panel" | "night" | "volt" | "ion" | "ember" | "ion-deep";

const surfaces: Record<TileTone, string> = {
  panel: "border border-edge bg-panel text-text",
  night: "bg-night text-on-night",
  volt: "bg-volt text-ink",
  ion: "bg-ion text-ink",
  ember: "bg-ember text-ink",
  "ion-deep": "bg-ion-deep text-on-ion-deep",
};

/** The colour of a tile's small lines (its label, the line under its title). */
export const tileMuted: Record<TileTone, string> = {
  panel: "text-text-2",
  night: "text-on-night-2",
  volt: "text-ink",
  ion: "text-ink",
  ember: "text-ink",
  "ion-deep": "text-on-ion-deep",
};

/** `lg`: the laptop's padding, 24 px and 28 px from 1200 px (web/DESIGN.md "Laptop layout"). */
export type TileSize = "md" | "lg";

export function tileClasses(tone: TileTone = "panel", size: TileSize = "md") {
  return cn("rounded-panel", size === "lg" ? "p-6 xl:p-7" : "p-4.5", surfaces[tone]);
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
  return <Tag data-tile={tone} className={cn(tileClasses(tone, size), className)} {...props} />;
}

/** A tile that opens something: it lifts a little on hover and presses in on tap (`tile-motion`). */
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
      data-tile={tone}
      className={cn(tileClasses(tone, size), "tile-motion flex flex-col justify-between", className)}
      {...props}
    >
      {children}
    </AppLink>
  );
}

/** A tile's small label ("Credit", "Running now"). */
export function TileLabel({
  tone = "panel",
  className,
  ...props
}: HTMLAttributes<HTMLSpanElement> & { tone?: TileTone }) {
  return <span className={cn("text-caption", tileMuted[tone], className)} {...props} />;
}

/** A section's title, with an optional action at its end. */
export function SectionTitle({
  children,
  action,
  id,
  className,
}: {
  children: ReactNode;
  action?: ReactNode;
  id?: string;
  className?: string;
}) {
  return (
    <div className={cn("flex min-h-touch items-center justify-between gap-4", className)}>
      <h2 id={id} className="text-section">
        {children}
      </h2>
      {action}
    </div>
  );
}
