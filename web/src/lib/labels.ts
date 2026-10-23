/**
 * The renter's words for what the API reports (web/DESIGN.md "Voice"): a session's state, and what moved their credit. Codes
 * the app doesn't know yet are shown readably rather than hidden. Why a session ended is src/lib/endings.ts, apart: its
 * words are a file of their own that only the session screen needs, so they stay out of the first load.
 */

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

export const readable = (code: string) => {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export const sessionState = (state: string) =>
  STATES[state] ?? { label: readable(state), tone: "neutral" as Tone };
