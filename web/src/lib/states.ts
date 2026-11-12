/**
 * The one map from a state to its chip (web/DESIGN.md "States and chips"): for each kind of thing, each code's words and
 * its tone. `StatusChip` draws the tone as a colour and an icon, so a state is never shown by colour alone. Every screen
 * of every site shows a state through here; a code the map doesn't know yet is shown in plain words, neutral.
 *
 * A session's words are the renter's ("Finding your GPU" for both REQUESTED and MATCHED); `exact` is the console's,
 * where the difference matters.
 */
export type Tone = "live" | "success" | "warning" | "danger" | "neutral";
export interface StateWord {
  label: string;
  tone: Tone;
  /** The console's words, where they're more exact than the renter's. */
  exact?: string;
}

const SESSION: Record<string, StateWord> = {
  REQUESTED: { label: "Finding your GPU", tone: "live", exact: "Waiting for a host" },
  MATCHED: { label: "Finding your GPU", tone: "live", exact: "Matched" },
  PROVISIONING: { label: "Starting", tone: "live" },
  RUNNING: { label: "Running", tone: "live" },
  HOST_LOST: { label: "Moving to another GPU", tone: "warning", exact: "Host lost" },
  FINISHING: { label: "Saving", tone: "live", exact: "Finishing" },
  COMPLETED: { label: "Done", tone: "success", exact: "Completed" },
  FAILED: { label: "Failed", tone: "danger" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  NO_HOST_FREE: { label: "No GPU was free", tone: "neutral", exact: "No host free" },
  ENDED_REFUNDED: { label: "Ended and refunded", tone: "neutral" },
};

/** A host's owner's machine (src/lib/hosting.ts, `MachineKind`); its label there says more ("Paused until 6 pm"). */
const MACHINE: Record<string, StateWord> = {
  running: { label: "Running a session", tone: "live" },
  idle: { label: "Idle", tone: "neutral" },
  paused: { label: "Paused", tone: "warning" },
  update: { label: "Update needed", tone: "warning" },
  suspended: { label: "Suspended", tone: "danger" },
  unknown: { label: "Status unknown", tone: "neutral" },
  offline: { label: "Offline", tone: "neutral" },
  pending: { label: "Checking", tone: "neutral" },
};

const DOMAINS = {
  session: SESSION,
  machine: MACHINE,
  /** Whether a host's agent is connected, as the console sees it: never "offline" while it can't tell. */
  presence: {
    online: { label: "Online", tone: "success" },
    unknown: { label: "Status unknown, checking…", tone: "neutral" },
    offline: { label: "Offline", tone: "neutral" },
  },
  /** A person's or a host's standing. */
  account: {
    active: { label: "Active", tone: "success" },
    waiting: { label: "Waiting for an invite", tone: "neutral" },
    suspended: { label: "Suspended", tone: "danger" },
    banned: { label: "Banned", tone: "danger" },
    held: { label: "Held", tone: "warning" },
    deleted: { label: "Deleted", tone: "neutral" },
    pending: { label: "Being checked", tone: "neutral" },
    removed: { label: "Removed", tone: "danger" },
  },
  /** A warning, suspension or ban (ADR 0053). */
  enforcement: {
    active: { label: "In force", tone: "danger" },
    lifted: { label: "Lifted", tone: "neutral" },
    expired: { label: "Ended", tone: "neutral" },
    reversed: { label: "Reversed", tone: "neutral" },
  },
  appeal: {
    open: { label: "Waiting for a decision", tone: "warning" },
    upheld: { label: "Upheld", tone: "neutral" },
    reversed: { label: "Reversed", tone: "success" },
    withdrawn: { label: "Withdrawn", tone: "neutral" },
  },
  /** A request for a second admin's approval (ADR 0051). */
  approval: {
    pending: { label: "Waiting", tone: "warning" },
    approved: { label: "Approved", tone: "success" },
    done: { label: "Done", tone: "success" },
    rejected: { label: "Turned down", tone: "neutral" },
    expired: { label: "Expired", tone: "neutral" },
    cancelled: { label: "Withdrawn", tone: "neutral" },
    failed: { label: "Failed", tone: "danger" },
  },
  /** A payout's status at RazorpayX (ADR 0040). */
  payout: {
    queued: { label: "Queued", tone: "neutral" },
    pending: { label: "Pending", tone: "neutral" },
    processing: { label: "On its way", tone: "live" },
    processed: { label: "Paid", tone: "success" },
    failed: { label: "Failed", tone: "danger" },
    rejected: { label: "Rejected", tone: "danger" },
    cancelled: { label: "Cancelled", tone: "danger" },
    reversed: { label: "Reversed", tone: "danger" },
  },
  /** A batch of payouts: previewed, then released by approval. */
  payoutRun: {
    preview: { label: "Preview", tone: "neutral" },
    released: { label: "Released", tone: "success" },
  },
  /** Whether RazorpayX holds enough for the next run. */
  balance: {
    enough: { label: "Enough", tone: "success" },
    short: { label: "Short", tone: "danger" },
  },
  /** A PAN's check against the name on the account. */
  panCheck: {
    matched: { label: "Name matches", tone: "success" },
    name_mismatch: { label: "Name doesn't match", tone: "danger" },
    not_checked: { label: "No provider yet", tone: "neutral" },
    invalid: { label: "Not a valid PAN", tone: "danger" },
  },
  /** A month of the books (ADR 0055). */
  month: {
    closed: { label: "Closed", tone: "success" },
    open: { label: "Open", tone: "neutral" },
    current: { label: "This month", tone: "live" },
  },
  /** The ledger's sides. */
  ledger: {
    balanced: { label: "Balanced", tone: "success" },
    unbalanced: { label: "Unbalanced", tone: "danger" },
  },
  /** A health check on the control room. */
  health: {
    ok: { label: "Working", tone: "success" },
    look: { label: "Look at this", tone: "danger" },
  },
  /** A notice to many people (ADR 0057). */
  broadcast: {
    draft: { label: "Draft", tone: "neutral" },
    scheduled: { label: "Scheduled", tone: "neutral" },
    sending: { label: "Sending", tone: "live" },
    sent: { label: "Sent", tone: "success" },
    cancelled: { label: "Cancelled", tone: "neutral" },
  },
  /** How soon a piece of work is wanted (ADR 0053). */
  priority: {
    urgent: { label: "Urgent", tone: "danger" },
    high: { label: "High", tone: "warning" },
    normal: { label: "Normal", tone: "neutral" },
    low: { label: "Low", tone: "neutral" },
  },
  /** A kill switch (ADR 0056). */
  flag: {
    running: { label: "On", tone: "success" },
    paused: { label: "Paused", tone: "danger" },
    off: { label: "Off", tone: "neutral" },
  },
  /** A thing to put right: still open, or settled. */
  mismatch: {
    open: { label: "Open", tone: "danger" },
    resolved: { label: "Resolved", tone: "neutral" },
  },
  /** One of the two witnesses to a billed minute (ADR 0017). */
  witness: {
    yes: { label: "Yes", tone: "success" },
    no: { label: "No", tone: "danger" },
  },
  /** How strongly a signal points at abuse. */
  signal: {
    STRONG: { label: "Strong", tone: "danger" },
    MEDIUM: { label: "Medium", tone: "warning" },
    WEAK: { label: "Weak", tone: "neutral" },
  },
  /** A ticket or a report, open or closed (ADR 0028). */
  ticket: {
    open: { label: "Open", tone: "warning" },
    waiting: { label: "Waiting on the person", tone: "neutral" },
    answered: { label: "Answered", tone: "neutral" },
    resolved: { label: "Resolved", tone: "success" },
    closed: { label: "Closed", tone: "neutral" },
  },
  /** A session a host's runner flagged for software no renter may run (ADR 0038). */
  abuse: {
    open: { label: "Open", tone: "danger" },
    confirmed: { label: "Confirmed", tone: "danger" },
    cleared: { label: "Cleared", tone: "neutral" },
  },
  /** A report of a problem with a session (ADR 0028). */
  report: {
    open: { label: "Open", tone: "danger" },
    resolved: { label: "Resolved", tone: "neutral" },
    refunded: { label: "Refunded", tone: "success" },
  },
  chargeback: {
    open: { label: "Open", tone: "danger" },
    evidence_submitted: { label: "Evidence sent", tone: "warning" },
    under_review: { label: "Under review", tone: "warning" },
    won: { label: "Won", tone: "success" },
    lost: { label: "Lost", tone: "danger" },
    closed: { label: "Closed", tone: "neutral" },
  },
  /** Earnings already paid out that a refund took back (ADR 0040). */
  clawback: {
    owed: { label: "Owed", tone: "danger" },
    recovered: { label: "Recovered", tone: "success" },
    waived: { label: "Waived", tone: "neutral" },
  },
  /** A renter's own image (ADR 0042). */
  image: {
    ready: { label: "Ready", tone: "success" },
    scanning: { label: "Being checked", tone: "live" },
    downloading: { label: "Downloading", tone: "live" },
    blocked: { label: "Blocked", tone: "danger" },
    failed: { label: "Check failed", tone: "danger" },
    removed: { label: "Removed", tone: "neutral" },
  },
  invite: {
    unused: { label: "Unused", tone: "neutral" },
    used: { label: "Used", tone: "success" },
    revoked: { label: "Revoked", tone: "neutral" },
    expired: { label: "Expired", tone: "neutral" },
  },
  /** A top-up at Razorpay (ADR 0039). */
  payment: {
    created: { label: "Started", tone: "neutral" },
    attempted: { label: "Tried", tone: "neutral" },
    authorized: { label: "Authorised", tone: "warning" },
    paid: { label: "Paid", tone: "success" },
    captured: { label: "Paid", tone: "success" },
    failed: { label: "Failed", tone: "danger" },
    refunded: { label: "Refunded", tone: "neutral" },
  },
  refund: {
    pending: { label: "Pending", tone: "neutral" },
    processing: { label: "On its way", tone: "live" },
    refunded: { label: "Refunded", tone: "success" },
    processed: { label: "Refunded", tone: "success" },
    failed: { label: "Failed", tone: "danger" },
  },
  /** A minute held for review: charged once both witnesses agree, or waived (ADR 0017). */
  minute: {
    pending: { label: "Waiting", tone: "warning" },
    charged: { label: "Charged", tone: "success" },
    waived: { label: "Waived", tone: "neutral" },
  },
  /** A Razorpay settlement against the ledger (ADR 0055). */
  settlement: {
    matched: { label: "Matched", tone: "success" },
    unmatched: { label: "Unmatched", tone: "danger" },
    pending: { label: "Pending", tone: "neutral" },
  },
  /** A piece of work against its target time (ADR 0053). */
  due: {
    overdue: { label: "Overdue", tone: "danger" },
    paused: { label: "Paused, waiting on the person", tone: "neutral" },
    reversed: { label: "Reversed", tone: "neutral" },
  },
} satisfies Record<string, Record<string, StateWord>>;

export type StateDomain = keyof typeof DOMAINS;

/** A code in plain words, for one the map doesn't know yet: "NO_HOST_FREE" is "No host free". */
export const readable = (code: string) => {
  const words = code.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};

export function stateOf(domain: StateDomain, code: string): StateWord {
  return (DOMAINS[domain] as Record<string, StateWord>)[code] ?? { label: readable(code), tone: "neutral" };
}

/** Every code each domain knows, for the gallery and the tests. */
export const STATE_DOMAINS: Readonly<Record<StateDomain, Readonly<Record<string, StateWord>>>> = DOMAINS;
