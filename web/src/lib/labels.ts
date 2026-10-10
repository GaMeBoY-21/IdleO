/**
 * The renter's words for what the API reports (web/DESIGN.md "Voice"): a session's state, why it ended, and
 * what moved their credit. Codes the app doesn't know yet are shown readably rather than hidden.
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
    sentence: "No host that fits accepted it.",
    next: "Run it again, or raise the most per hour so more hosts fit.",
  },
  no_candidate: {
    sentence: "No host that fits was free.",
    next: "Run it again in a while, or raise the most per hour so more hosts fit.",
  },
  no_host_for_rematch: {
    sentence: "Its host dropped and no other host was free to carry on. Lost minutes weren't charged.",
    next: "Run it again in a while; it starts from its last checkpoint if it saved one.",
  },
  insufficient_credit: {
    sentence: "There wasn't enough credit to hold for it when a host was found.",
    next: "Choose a lower budget, or a lower most per hour in Advanced, and book it again.",
  },
  credit_exhausted: {
    sentence: "Your credit ran out, so it saved a checkpoint and stopped.",
    next: "Once you have more credit, run it again; it can start from that checkpoint.",
  },
  max_spend_reached: {
    sentence: "It reached your spending cap, so it saved a checkpoint and stopped.",
    next: "Run it again with a higher budget if it needs longer.",
  },
  renter_cancelled: {
    sentence: "You cancelled it before it started.",
    next: "Book it again when you're ready.",
  },
  input_unavailable: {
    sentence: "The host couldn't fetch your input, so the job never started.",
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
