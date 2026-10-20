/**
 * Why a session ended, in the renter's words (web/DESIGN.md "Voice"). Apart from src/lib/labels.ts, which the first screen
 * imports: these words (api/endings.json) are only the session screen's, and `LATER` in vite.config.ts keeps them out of the
 * shell chunk, so the signed-in home's first load doesn't carry them.
 */

import endingWords from "../../../api/endings.json";
import { readable } from "./labels";

/** Why a session ended without completing, in plain words, and what to do next (product surface 3.2). */
export interface Ending {
  sentence: string;
  next: string;
}

/**
 * Every code the control plane can end a session with (its error code, or the reason of its last event), and the
 * codes the agent can report, each with a sentence and a next step. They live in api/endings.json, one file the
 * control plane reads too, so an email says what the screen says (ADR 0026). src/lib/labels.test.ts reads the
 * control plane's and the agent's sources and fails if a code has no words there.
 */
export const ENDINGS: Record<string, Ending> = endingWords.codes;

/** Why a session ended, from its error code or its last event's reason; an exit code is read too. */
export function ending(code: string | null | undefined): Ending | null {
  if (!code) {
    return null;
  }
  const exit = /^exit_code_(-?\d+)$/.exec(code);
  if (exit) {
    return { sentence: endingWords.exit.sentence.replace("{code}", exit[1]!), next: endingWords.exit.next };
  }
  return (
    ENDINGS[code] ?? {
      sentence: `It ended: ${readable(code).toLowerCase()}.`,
      next: endingWords.unknown.next,
    }
  );
}
