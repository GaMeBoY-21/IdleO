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
  job_succeeded: "The job finished",
  outputs_complete: "Its outputs are saved",
  renter_cancelled: "You cancelled it",
  lease_expired_on_host: "The host stopped it",
  stopped_on_host: "The host's owner took the machine back",
  missing_after_reconnect: "The host came back without it",
  host_lost_repeatedly: "It lost its host too many times",
  offers_exhausted: "No host accepted it",
  no_candidate: "No host was free",
  no_host_for_rematch: "No other host was free",
  insufficient_credit: "There wasn't enough credit",
  input_unavailable: "The host couldn't fetch the input",
};

export const eventReason = (reason: string) => REASONS[reason] ?? readable(reason);

/** Why a session ended without completing, in plain words, and what to do next (product surface 3.2). */
export interface Ending {
  sentence: string;
  next: string;
}

const RUN_AGAIN = "Run it again; you won't be charged for the attempt that failed to start.";
const CHECK_JOB = "Check its log for the last lines it wrote, fix the cause, and run it again.";
const OUR_SIDE = "This was a problem on our side, not yours. Run it again; if it keeps happening, tell us.";

/**
 * Every code the control plane can end a session with (its error code, or the reason of its last event), and the
 * codes the agent can report, each with a sentence and a next step. src/lib/labels.test.ts reads the control
 * plane's and the agent's sources and fails if a code has no words here.
 */
export const ENDINGS: Record<string, Ending> = {
  job_failed: { sentence: "The job stopped with an error.", next: CHECK_JOB },
  oom_killed: {
    sentence: "The job ran out of memory.",
    next: "Choose more GPU memory, or a smaller model or batch size, and run it again.",
  },
  outputs_missing: {
    sentence: "The job finished, but didn't save any outputs.",
    next: "Make sure it writes its results to the outputs folder, then run it again.",
  },
  outputs_upload_failed: {
    sentence: "The job finished, but its outputs couldn't be saved.",
    next: OUR_SIDE,
  },
  host_lost_repeatedly: {
    sentence: "It lost its host too many times to carry on. Minutes lost with a host weren't charged.",
    next: "Run it again; it starts from its last checkpoint if it saved one.",
  },
  start_timeout: { sentence: "The host didn't start it in time.", next: RUN_AGAIN },
  offers_exhausted: {
    sentence: "No host that fits accepted it. Nothing was charged.",
    next: "Run it again, or raise the most per hour so more hosts fit.",
  },
  no_candidate: {
    sentence: "No host that fits was free. Nothing was charged.",
    next: "Run it again in a while, or raise the most per hour so more hosts fit.",
  },
  no_host_for_rematch: {
    sentence: "Its host dropped and no other host was free to carry on. Lost minutes weren't charged.",
    next: "Run it again in a while; it starts from its last checkpoint if it saved one.",
  },
  insufficient_credit: {
    sentence: "There wasn't enough credit to hold for it when a host was found. Nothing was charged.",
    next: "Lower the most per hour or the spending cap, and book it again.",
  },
  credit_exhausted: {
    sentence: "Your credit ran out, so it saved a checkpoint and stopped.",
    next: "Once you have more credit, run it again; it can start from that checkpoint.",
  },
  max_spend_reached: {
    sentence: "It reached your spending cap, so it saved a checkpoint and stopped.",
    next: "Run it again with a higher spending cap if it needs longer.",
  },
  renter_cancelled: {
    sentence: "You cancelled it before it started. Nothing was charged.",
    next: "Book it again when you're ready.",
  },
  input_unavailable: {
    sentence: "The host couldn't fetch your input, so the job never started. Nothing was charged.",
    next: "Check the file is still uploaded, or upload it again, and run it again.",
  },
  input_unreadable: {
    sentence: "The job couldn't read your input.",
    next: "Check the file isn't damaged and is the kind the template takes, then run it again.",
  },
  resume_checkpoint_missing: {
    sentence: "Its checkpoint couldn't be found to carry on from.",
    next: OUR_SIDE,
  },
  storage_failed: { sentence: "The job couldn't save its work to storage.", next: OUR_SIDE },
  no_runner: { sentence: "The host couldn't run this template.", next: OUR_SIDE },
  invalid_start_session: { sentence: "The host was sent instructions it couldn't follow.", next: OUR_SIDE },
  invalid_args: { sentence: "The template's settings didn't make sense to the job.", next: OUR_SIDE },
  invalid_spec: { sentence: "The template is set up wrongly.", next: OUR_SIDE },
  invalid_error_code: { sentence: "The job failed, and the host didn't say why.", next: CHECK_JOB },
};

/** Why a session ended, from its error code or its last event's reason; an exit code is read too. */
export function ending(code: string | null | undefined): Ending | null {
  if (!code) {
    return null;
  }
  const exit = /^exit_code_(-?\d+)$/.exec(code);
  if (exit) {
    return { sentence: `The job exited with code ${exit[1]!}.`, next: CHECK_JOB };
  }
  return ENDINGS[code] ?? { sentence: `It ended: ${readable(code).toLowerCase()}.`, next: CHECK_JOB };
}

const KINDS: Record<string, string> = {
  free_credit: "Free credit",
  free_credit_reversed: "Free credit withdrawn",
  session_charge: "Session charge",
  session_refund: "Refund for lost host time",
};

export const creditKind = (kind: string) => KINDS[kind] ?? readable(kind);
