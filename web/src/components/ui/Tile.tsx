import type { HTMLAttributes, ReactNode } from "react";
import { Link } from "wouter";
import { cn } from "@/lib/cn";

/**
 * Tiles (web/DESIGN.md "Shapes and space", ADR 0031): the one surface of the Bento look. A white tile sits on the page;
 * the dark tile is what matters now; lime, lavender and apricot are the main action, history, and hosting and earning.
 * Every tile has 26 px corners and 18 px of padding, and no border. Anything inside a tile that is filled (a field, a
 * secondary button) is pressed in with the page's colour: a tile marks its inside with `data-tile`, which the `tiled`
 * variant (styles.css) reads, so those components never need to know where they are.
 */
export type TileTone = "white" | "dark" | "lime" | "lavender" | "apricot";

const surfaces: Record<TileTone, string> = {
  white: "bg-fill text-text",
  dark: "bg-dark text-dark-fg",
  lime: "bg-lime text-on-colour",
  lavender: "bg-lavender text-on-colour",
  apricot: "bg-apricot text-on-colour",
};

/** The colour of a tile's small lines (its label, the line under its title). */
export const tileMuted: Record<TileTone, string> = {
  white: "text-text-muted",
  dark: "text-dark-muted",
  lime: "text-lime-fg",
  lavender: "text-lavender-fg",
  apricot: "text-apricot-fg",
};

/** `lg`: the laptop's padding, 24 px and 28 px from 1200 px (web/DESIGN.md "Laptop layout"). */
export type TileSize = "md" | "lg";

export function tileClasses(tone: TileTone = "white", size: TileSize = "md") {
  return cn("rounded-tile", size === "lg" ? "p-6 xl:p-7" : "p-4.5", surfaces[tone]);
}

export function Tile({
  tone = "white",
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
  tone = "white",
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
    <Link
      href={href}
      data-tile={tone}
      className={cn(tileClasses(tone, size), "tile-motion flex flex-col justify-between", className)}
      {...props}
    >
      {children}
    </Link>
  );
}

/** A tile's small label ("Credit", "Running now"). */
export function TileLabel({
  tone = "white",
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
