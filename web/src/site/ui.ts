import { cn } from "@/lib/cn";

/**
 * The public pages' few shared looks (web/DESIGN.md, ADR 0043): the same tokens and the same panels and buttons as the
 * app, written as class strings so the pages are plain HTML with no script. The app's components aren't imported:
 * nothing of the app reaches these pages (ADR 0029).
 */
const button = "inline-flex min-h-touch items-center justify-center gap-2 rounded-control px-5 text-small";

export const primaryButton = cn(button, "bg-primary font-bold text-primary-fg hover:bg-primary-hover");
/** The raised button beside the primary one: white with an edge by day. */
export const ghostButton = cn(
  button,
  "border border-edge bg-ghost font-semibold text-text hover:bg-ghost-hover",
);
/** Ion-deep with white text, for a button on a band or a panel of its own. */
export const secondaryButton = cn(button, "bg-ion-deep font-bold text-on-ion-deep hover:bg-ion-deep-hover");
/** Volt under dark text in either theme: the button on a band that is a colour of its own. */
export const liveButton = cn(button, "bg-volt font-bold text-ink hover:bg-volt-hover");
export const largeButton = "min-h-14 rounded-control-lg px-6 text-option md:min-h-15";
export const link = "text-link underline underline-offset-4 hover:text-link-hover";

/** The panels (web/DESIGN.md "Shapes and space"): a panel, the always-dark ones, and the loud colours. */
export const tile = "rounded-panel border border-edge bg-panel p-6 text-text";
export const tileNight = "scope-night rounded-hero bg-night p-6 text-on-night md:p-8";
export const tileVolt = "rounded-feature bg-volt p-6 text-ink md:p-8";
export const tileIon = "rounded-feature bg-ion p-6 text-ink md:p-8";
export const tileEmber = "rounded-feature bg-ember p-6 text-ink md:p-8";
/** The grid the pages lay their panels on: 16 px apart, 24 from 768 px. */
export const grid = "grid gap-4 md:gap-5";

/** The page's frame, as drawn: 1280 px with 32 px at the sides (16 on a phone). */
export const column = "mx-auto w-full max-w-site px-4 md:px-8";
/** The docs' column of text, inside the frame. */
export const readable = "mx-auto max-w-content";
/** A band of the page, with room above and below. */
export const band = "py-10 md:py-16";
/** A page's headline: Archivo at width 125%, uppercase. */
export const heading = "font-display text-display-page text-text uppercase";
/** A section's headline on the landing page, and its smaller sibling. */
export const bandTitle = "font-display text-display-band text-text uppercase";
export const sectionHeading = "font-display text-display-section text-text uppercase";
/** A sub-section's title on an inner page, in the title face. */
export const sectionTitle = "font-title text-display-card text-text";
export const muted = "text-body text-text-2";
/** A small label above a headline: "FOR BUILDERS". */
export const label = "text-caption font-bold tracking-widest uppercase";
