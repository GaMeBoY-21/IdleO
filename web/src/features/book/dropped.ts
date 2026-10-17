import type { Chosen } from "./chosen-files";

/**
 * What was dropped on Home (web/DESIGN.md "Home"): held until the booking screen it opens takes it as its first upload
 * step. A drop is a pointer shortcut, so it only lives for the one navigation: the screen takes it once, or it is
 * forgotten after a few seconds.
 */
let held: { chosen: Chosen; at: number } | null = null;
const KEPT_MS = 10_000;

export function holdDrop(chosen: Chosen): void {
  held = { chosen, at: Date.now() };
}

export function takeDrop(): Chosen | null {
  const taken = held && Date.now() - held.at < KEPT_MS ? held.chosen : null;
  held = null;
  return taken;
}

/** A folder with a `config.json` at its top is a model: Fine-tune takes it; anything else is code or data. */
export const isModelFolder = (chosen: Chosen): boolean =>
  chosen.folder && chosen.files.some(({ path }) => path === "config.json");
