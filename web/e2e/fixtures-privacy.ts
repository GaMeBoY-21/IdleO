import { NOW } from "./fixtures";

/**
 * Invented data for Account → Your data and deleting an account (ADR 0037): what the server answers for Asha, whose
 * figures in fixtures.ts these count.
 */
const DAY = 86_400_000;
const ago = (days: number) => new Date(NOW.getTime() - days * DAY).toISOString();
const ahead = (days: number) => new Date(NOW.getTime() + days * DAY).toISOString();

const item = (label: string, count: number, from: number | null, to: number | null) => ({
  label,
  count,
  earliest: from === null ? null : ago(from),
  latest: to === null ? null : ago(to),
});

/** What we hold about Asha, by group. */
export const yourData = () => ({
  groups: [
    {
      group: "account",
      items: [item("API keys", 2, 20, 3), item("account", 1, 36, 0), item("security log entries", 14, 36, 0)],
    },
    { group: "sessions", items: [item("sessions", 6, 30, 0)] },
    { group: "files", items: [item("uploads", 3, 25, 2)] },
    { group: "money", items: [item("ledger lines", 9, 36, 0)] },
    { group: "hosting", items: [item("earnings lines", 0, null, null), item("machines", 0, null, null)] },
    { group: "messages", items: [item("messages", 11, 30, 1)] },
    { group: "reports", items: [item("ratings", 2, 10, 4), item("reports", 1, 9, 9)] },
  ],
});

const exportOf = (state: string, extra: Record<string, unknown> = {}) => ({
  id: "01a11300-0000-7000-8000-0000000e0001",
  state,
  requestedAt: ago(0.01),
  readyAt: null,
  expiresAt: null,
  sizeBytes: null,
  ...extra,
});

export const noExports = () => ({ exports: [] });
export const exportBeingMade = () => ({ exports: [exportOf("requested")] });
export const exportReady = () => ({
  exports: [exportOf("ready", { readyAt: ago(0.005), expiresAt: ahead(7), sizeBytes: 48_213 })],
});

/** The legal periods as the server is configured with them (control-plane application.yaml's legal:). */
const KEPT_FOR = { moneyRecordsDays: 2922, securityLogDays: 365, backupsDays: 30 };

const request = (state: string, extra: Record<string, unknown> = {}) => ({
  id: "01a11300-0000-7000-8000-0000000d0001",
  userId: "01a11300-0000-7000-8000-00000000000a",
  state,
  legalOrder: false,
  requestedAt: ago(0.01),
  confirmedAt: null,
  deleteAfter: null,
  cancelledAt: null,
  step: 0,
  erasedAt: null,
  ...extra,
});

/** Asha's ₹500 of free credit, which deleting gives up. */
const FORFEIT = {
  code: "credit-forfeited",
  message: "Your ₹500.00 of free credit can't be paid out, so it's forfeited.",
  amountPaise: 50_000,
};

export const deletionNone = (freshSignIn = true) => ({
  request: null,
  blockers: [],
  losses: [FORFEIT],
  waits: [] as { code: string; message: string; amountPaise: number | null; until: string | null }[],
  waitingDays: 7,
  keptFor: KEPT_FOR,
  freshSignIn,
});

/**
 * A host deleting their account (ADR 0040): what's available is paid when the wait ends, what's pending on its date.
 * The figures are the payouts fixtures' own.
 */
export const deletionHost = () => ({
  ...deletionNone(),
  losses: [],
  waits: [
    {
      code: "final-payout",
      message:
        "₹128.00 your machines earned is paid to your UPI ID when the waiting period ends, before the account is deleted.",
      amountPaise: 12_800,
      until: null,
    },
    {
      code: "earnings-pending",
      message:
        "₹43.20 is still in its dispute window and is paid once it's available; the account is deleted after that.",
      amountPaise: 4_320,
      until: "2026-10-31T09:12:00Z",
    },
  ],
});

export const deletionBlocked = () => ({
  ...deletionNone(),
  blockers: [
    {
      code: "session-running",
      message: "You have a session that hasn't ended. Stop it, or wait for it to finish.",
      amountPaise: null,
    },
    {
      code: "open-report",
      message:
        "You have a report we haven't answered yet. We'll answer it soon; you can delete your account after that.",
      amountPaise: null,
    },
  ],
});

export const deletionAwaiting = () => ({ ...deletionNone(), request: request("awaiting_confirmation") });

export const deletionScheduled = () => ({
  ...deletionNone(),
  request: request("scheduled", { confirmedAt: ago(0.005), deleteAfter: ahead(7) }),
});

export const deletionCancelled = () => ({
  ...deletionNone(),
  request: request("cancelled", { confirmedAt: ago(0.005), cancelledAt: ago(0) }),
});
