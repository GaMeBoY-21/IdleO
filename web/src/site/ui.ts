import { cn } from "@/lib/cn";

/**
 * The public pages' few shared looks (web/DESIGN.md, ADR 0060): the same tokens and the same panels and buttons as the
 * app, written as class strings so the pages are plain HTML with no script. The app's components aren't imported:
 * nothing of the app reaches these pages (ADR 0029).
 */
const button =
  "inline-flex min-h-control items-center justify-center gap-2 rounded-control px-button text-button transition-colors duration-press pointer-coarse:min-h-touch";

/** The one primary button of a screen: volt, with canvas text. */
export const primaryButton = cn(button, "bg-volt text-on-volt hover:bg-volt-hover active:bg-volt-pressed");
/** The secondary button beside it: surface-1 with a hairline border. */
export const secondaryButton = cn(
  button,
  "border border-hairline-strong bg-surface-1 text-ink hover:bg-surface-2",
);
export const largeButton = "min-h-10 rounded-control px-4";
export const link = "text-ink underline underline-offset-4 hover:text-ink-muted";

/** The cards (web/DESIGN.md "Shapes and space"): a card on the canvas, and a big panel one step up. */
export const tile = "rounded-card border border-hairline bg-surface-1 p-6 text-ink";
export const tileRaised = "rounded-panel border border-hairline bg-surface-2 p-6 text-ink shadow-lift md:p-8";
/** The grid the pages lay their panels on: 16 px apart. */
export const grid = "grid grid-cols-1 gap-4 md:gap-4";

/** The page's frame, as drawn: 1280 px with 32 px at the sides (16 on a phone). */
export const column = "mx-auto w-full max-w-site px-4 md:px-8";
/** The docs' column of text, inside the frame. */
export const readable = "mx-auto max-w-content";
/** A band of the page, with room above and below. */
export const band = "py-10 md:py-16";
/** A page's headline: 28 px at 600, tight. */
export const heading = "text-title text-ink";
/** A section's headline on the landing page, and its smaller sibling. */
export const bandTitle = "text-hero text-ink";
export const sectionHeading = "text-hero text-ink";
/** A sub-section's title on an inner page. */
export const sectionTitle = "text-section text-ink";
export const muted = "text-body text-ink-subtle";
/** A small label above a headline: 12 px, tracked out a little, in sentence case. */
export const label = "text-eyebrow text-ink-subtle";
