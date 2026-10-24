import type { PayoutDetails, PayoutSummary, PayoutView } from "../src/api/payouts";
import { COMMISSION_PERCENT } from "../src/lib/hosting";

/**
 * A host's payouts (ADR 0040), invented for the screens and their tests. Every figure is derived from the sessions and
 * payouts below, never typed in twice: a payout's net is its gross less the tax, the summary's pending is its pending
 * sessions' sum, and the year's tax is the payouts'.
 */
const share = (chargedPaise: number) => Math.floor((chargedPaise * (100 - COMMISSION_PERCENT)) / 100);

export const ROG = { id: "01a16ef8-75f6-7026-ab24-fb093b78638f", name: "ROG Strix" };
export const DESK = { id: "01a16ef8-761b-710e-90be-b93f8ade06e7", name: "Home desktop" };

export const pendingSessions = [
  {
    hostId: ROG.id,
    machineName: ROG.name,
    sessionId: "01a16ef8-7720-726f-9630-3bc690584bdd",
    amountPaise: share(3_600),
    availableAt: "2026-10-12T09:12:00Z",
  },
  {
    hostId: DESK.id,
    machineName: DESK.name,
    sessionId: "01a16ef8-7722-70da-b4e6-18d6a8af000a",
    amountPaise: share(1_800),
    availableAt: null,
  },
];

const payout = (
  id: string,
  grossPaise: number,
  tdsPaise: number,
  status: string,
  createdAt: string,
  extra: Partial<PayoutView> = {},
): PayoutView => ({
  id,
  kind: "weekly",
  grossPaise,
  tdsPaise,
  tdsSection: "194-O",
  feePaise: 0,
  netPaise: grossPaise - tdsPaise,
  status,
  why: null,
  utr: status === "processed" ? "523223155921" : null,
  createdAt,
  processedAt: status === "processed" ? createdAt : null,
  endedAt: null,
  ...extra,
});

export const payouts: PayoutView[] = [
  payout("01a16f00-0000-7000-8000-000000000003", 12_800, 0, "processing", "2026-10-05T04:30:00Z"),
  payout("01a16f00-0000-7000-8000-000000000002", 9_600, 0, "failed", "2026-09-28T04:30:00Z", {
    why: "It didn't go through: Payout failed as the beneficiary account is closed. Please contact the beneficiary bank. It's back in your earnings, and the next payout tries again.",
  }),
  payout("01a16f00-0000-7000-8000-000000000001", 20_000, 0, "processed", "2026-09-21T04:30:00Z"),
];

const sum = (values: number[]) => values.reduce((total, value) => total + value, 0);
const counted = payouts.filter(
  (item) => !["failed", "rejected", "cancelled", "reversed"].includes(item.status),
);

export const summary: PayoutSummary = {
  pendingPaise: sum(pendingSessions.map((session) => session.amountPaise)),
  availablePaise: share(16_000),
  owedPaise: 0,
  payablePaise: share(16_000),
  inFlightPaise: sum(payouts.filter((item) => item.status === "processing").map((item) => item.netPaise)),
  paidPaise: sum(payouts.filter((item) => item.status === "processed").map((item) => item.netPaise)),
  pending: pendingSessions,
  nextPayoutAt: "2026-10-12T04:30:00Z",
  minimumPaise: 10_000,
  blockedBy: null,
  holdUntil: null,
  financialYear: "2026-27",
  yearGrossPaise: sum(counted.map((item) => item.grossPaise)),
  yearTdsPaise: sum(counted.map((item) => item.tdsPaise)),
  statementYears: ["2026-27", "2025-26"],
};

export const summaryNoPan: PayoutSummary = { ...summary, blockedBy: "no-pan", inFlightPaise: 0 };

export const detailsNew: PayoutDetails = {
  pan: null,
  inUse: null,
  inReview: null,
  latestCheck: null,
  payoutsAvailable: true,
  nameMatchThreshold: 80,
  checksPerDay: 5,
  changeHoldHours: 48,
};

const PAN = {
  last4: "234F",
  name: "Asha Rao",
  holder: "individual",
  state: "verified",
  reason: null,
  submittedAt: "2026-10-01T08:00:00Z",
  decidedAt: "2026-10-01T08:00:00Z",
};

export const checked = {
  id: "01a16f10-0000-7000-8000-000000000001",
  vpa: "asha@okicici",
  state: "checked",
  registeredName: "ASHA RAO",
  nameMatchScore: 100,
  failure: null,
  checkedAt: "2026-10-07T06:30:00Z",
  confirmedAt: null,
  holdUntil: null,
  reason: null,
};

export const detailsChecked: PayoutDetails = { ...detailsNew, pan: PAN, latestCheck: checked };

export const detailsActive: PayoutDetails = {
  ...detailsNew,
  pan: PAN,
  inUse: { ...checked, state: "active", confirmedAt: "2026-10-07T06:31:00Z" },
};

export const detailsHeld: PayoutDetails = {
  ...detailsActive,
  inUse: { ...checked, vpa: "asha.new@ybl", state: "active", holdUntil: "2026-10-09T06:31:00Z" },
};
