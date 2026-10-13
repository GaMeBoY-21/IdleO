/**
 * The renter's words for what the API reports (web/DESIGN.md "Voice"): a session's state, why it ended, and
 * what moved their credit. Codes the app doesn't know yet are shown readably rather than hidden.
 */

import endingWords from "../../../api/endings.json";

export type Tone = "live" | "success" | "danger" | "warning" | "neutral";

const STATES: Record<string, { label: string; tone: Tone }> = {
  REQUESTED: { label: "Finding your GPU", tone: "live" },
  MATCHED: { label: "Finding your GPU", tone: "live" },
  PROVISIONING: { label: "Starting", tone: "live" },
  RUNNING: { label: "Running", tone: "live" },
  HOST_LOST: { label: "Moving to another GPU", tone: "warning" },
  FINISHING: { label: "Saving", tone: "live" },
  COMPLETED: { label: "Done", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  NO_HOST_FREE: { label: "No GPU was free", tone: "neutral" },
  ENDED_REFUNDED: { label: "Ended and refunded", tone: "neutral" },
};

const readable = (code: string) => {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export const sessionState = (state: string) =>
  STATES[state] ?? { label: readable(state), tone: "neutral" as Tone };

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

const KINDS: Record<string, string> = {
  free_credit: "Free credit",
  free_credit_reversed: "Free credit withdrawn",
  session_charge: "Session charge",
  session_refund: "Refund for lost host time",
  dispute_refund: "Refund after your report",
};

export const creditKind = (kind: string) => KINDS[kind] ?? readable(kind);
