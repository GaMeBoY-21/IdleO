/**
 * The renter's words for what the API reports (web/DESIGN.md "Voice"): a session's state, why it changed, and
 * what moved their credit. Codes the app doesn't know yet are shown readably rather than hidden.
 */

export type Tone = "live" | "success" | "danger" | "warning" | "neutral";

const STATES: Record<string, { label: string; tone: Tone }> = {
  REQUESTED: { label: "Finding a host", tone: "live" },
  MATCHED: { label: "Host found", tone: "live" },
  PROVISIONING: { label: "Starting", tone: "live" },
  RUNNING: { label: "Running", tone: "live" },
  HOST_LOST: { label: "Moving to another host", tone: "warning" },
  FINISHING: { label: "Finishing", tone: "live" },
  COMPLETED: { label: "Completed", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  NO_HOST_FREE: { label: "No host was free", tone: "neutral" },
  ENDED_REFUNDED: { label: "Ended and refunded", tone: "neutral" },
};

const readable = (code: string) => {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export const sessionState = (state: string) =>
  STATES[state] ?? { label: readable(state), tone: "neutral" as Tone };

const REASONS: Record<string, string> = {
  requested: "You booked it",
  matched: "A host accepted it",
  offer_accepted: "The host is starting it",
  running: "It started running",
  renter_stopped: "You asked it to stop",
  stopped: "It stopped after a last checkpoint",
  credit_exhausted: "Your credit ran out",
  max_spend_reached: "It reached your spending limit",
  lease_lapsed: "The host lost contact",
  host_lost: "The host lost contact",
  rematch: "Looking for another host",
  completed: "It finished and its outputs are saved",
  succeeded: "It finished",
  outputs_missing: "It finished without saving its outputs",
  cancelled: "You cancelled it",
  start_timeout: "The host didn't start it in time",
  no_host_free: "No host was free",
  oom_killed: "It ran out of memory",
  job_failed: "It stopped with an error",
};

export const eventReason = (reason: string) => REASONS[reason] ?? readable(reason);

const KINDS: Record<string, string> = {
  free_credit: "Free credit",
  free_credit_reversed: "Free credit withdrawn",
  session_charge: "Session charge",
  session_refund: "Refund for lost host time",
};

export const creditKind = (kind: string) => KINDS[kind] ?? readable(kind);
