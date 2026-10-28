import { fonts } from "./tokens";

/**
 * Asks for the headline faces the first screen doesn't preload (the uppercase of Archivo at 125%, and Archivo at 112% for
 * titles), once the first screen has painted. A screen that is drawn after its data arrives (a session finding its GPU)
 * then has them already, and its titles don't move as the font swaps in. Never before the paint: it would compete with it
 * (ADR 0018). The browser fetches only the files whose letters the sample text has.
 */
export function loadHeadlineFonts(): void {
  if (typeof document === "undefined" || !("fonts" in document)) {
    return;
  }
  for (const [family, weight] of [
    [fonts.title.family, fonts.title.weight],
    [fonts.display.family, fonts.display.weight],
  ] as const) {
    void document.fonts.load(`${weight} 1em "${family}"`, "Aa0").catch(() => undefined);
  }
}
