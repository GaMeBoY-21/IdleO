import { fonts } from "./tokens";
import { layout } from "./tokens";

/**
 * Asks for the uppercase of Archivo at 125% (the headlines from 768 px: `PageHeading`, the live session's band), which the
 * first screen doesn't preload, once the first screen has painted. A screen that is drawn after its data arrives then has
 * it already, and its headline doesn't move as the font swaps in. A phone never sets that face, so it never asks (and what it
 * asks for would compete with the data its screen waits on). Never before the paint (ADR 0018). The browser fetches
 * only the files whose letters the sample text has.
 */
export function loadHeadlineFonts(): void {
  if (typeof document === "undefined" || !("fonts" in document)) {
    return;
  }
  if (!window.matchMedia(`(min-width: ${layout.breakpoints.md}px)`).matches) {
    return;
  }
  void document.fonts
    .load(`${fonts.display.weight} 1em "${fonts.display.family}"`, "Aa0")
    .catch(() => undefined);
}
