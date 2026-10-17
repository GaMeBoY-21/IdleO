import { cn } from "@/lib/cn";

/**
 * The public pages' few shared looks (web/DESIGN.md, ADR 0031): the same tokens and the same tiles and buttons as the
 * app, written as class strings so the pages are plain HTML with no script. The app's components aren't imported:
 * nothing of the app reaches these pages (ADR 0029).
 */
const button =
  "inline-flex min-h-touch items-center justify-center gap-2 rounded-full px-4 text-small font-semibold";

export const primaryButton = cn(button, "bg-primary text-primary-fg hover:bg-primary-hover");
export const secondaryButton = cn(button, "bg-fill text-text hover:bg-fill-selected");
/** The lime button that sits inside a dark tile. */
export const liveButton = cn(button, "bg-live text-on-colour hover:brightness-95");
export const largeButton = "min-h-14 px-6 text-option md:min-h-15";
export const link = "underline underline-offset-4";

/** The tiles (web/DESIGN.md "Shapes and space"): white, dark, and the three soft colours, each with its small text. */
export const tile = "rounded-tile bg-fill p-5 text-text";
export const tileDark = "rounded-tile bg-dark p-6 text-dark-fg md:p-8";
export const tileLime = "rounded-tile bg-lime p-5 text-on-colour";
export const tileLavender = "rounded-tile bg-lavender p-5 text-on-colour";
export const tileApricot = "rounded-tile bg-apricot p-6 text-on-colour md:p-8";
/** The grid the bento pages lay their tiles on: a 10 px gap on a phone, 14 px from 768 px. */
export const grid = "grid gap-2.5 md:gap-3.5 xl:gap-4 2xl:gap-5";

/**
 * The page's frame, as the app's: the full width of the window with no maximum, 14 px margins on a phone, 24 from
 * 768 px, 32 from 1200 px and 40 from 1680 px (web/DESIGN.md "Laptop layout"). The docs keep a readable column inside it.
 */
export const column = "w-full px-3.5 md:px-6 xl:px-8 2xl:px-10";
/** The docs' column of text, inside the frame. */
export const readable = "mx-auto max-w-content";
/** A band of the page, with room above and below. */
export const band = "py-8 md:py-12";
export const heading = "text-title text-text md:text-question";
export const sectionTitle = "text-section text-text";
export const muted = "text-body text-text-muted";
