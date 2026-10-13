import { cn } from "@/lib/cn";

/**
 * The public pages' few shared looks (web/DESIGN.md): the same tokens and the same buttons as the app, written as
 * class strings so the pages are plain HTML with no script. The app's components aren't imported: nothing of the app
 * reaches these pages (ADR 0029).
 */
const button =
  "inline-flex min-h-touch items-center justify-center gap-2 rounded-control px-4 text-small font-semibold";

export const primaryButton = cn(button, "bg-primary text-primary-fg hover:bg-primary-hover");
export const secondaryButton = cn(button, "bg-fill text-text hover:bg-fill-selected");
export const largeButton = "min-h-14 px-6 text-option md:min-h-15";
export const link = "underline underline-offset-4";

/** The page's column: 16 px margins on a phone, 24 from 768 px, 32 from 1280. */
export const column = "mx-auto w-full max-w-content px-4 md:px-6 xl:px-8";
/** A band of the page, with room above and below. */
export const band = "py-10 md:py-14";
export const heading = "text-title text-text md:text-question";
export const sectionTitle = "text-section font-semibold text-text";
export const muted = "text-body text-text-muted";
