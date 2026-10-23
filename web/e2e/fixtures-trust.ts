import type { components } from "../src/api/schema.gen";
import * as data from "./fixtures";

/**
 * Ratings and reports, invented (ADR 0028), as the API serves them: what Asha sees on a finished session, Ravi's
 * refunded report, and what an admin reads. Every figure follows from the others: the refund is the fixture's own
 * `REPORT_REFUND`, and its split is the commission first, then the host, as billing does it.
 */
type Schemas = components["schemas"];
export type SessionRating = Schemas["SessionRating"];
export type Dispute = Schemas["DisputeResponse"];

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const at = (ms: number) => new Date(ms).toISOString();

/** A rating can be given, or changed, for 7 days from the session's end, or from when it was given. */
const WINDOW = 7 * DAY;

const endedAt = (session: data.Session) => new Date(session.endedAt!).getTime();

/** A completed session nobody has rated yet. */
export const ratingOpen = (session: data.Session): SessionRating => ({
  sessionId: session.id,
  rateable: true,
  rateableUntil: at(endedAt(session) + WINDOW),
});

/** One rated 4 stars an hour ago, with a comment, still changeable. */
export const ratingGiven = (session: data.Session): SessionRating => {
  const ratedAt = data.NOW.getTime() - HOUR;
  return {
    sessionId: session.id,
    rateable: true,
    rateableUntil: at(ratedAt + WINDOW),
    stars: 4,
    comment: "Started fast, and the logs were easy to follow.",
    ratedAt: at(ratedAt),
    changedAt: at(ratedAt),
  };
};

/** One rated 5 stars more than 7 days ago: it can't change any more. */
export const ratingClosed = (session: data.Session): SessionRating => {
  const ratedAt = data.NOW.getTime() - 9 * DAY;
  return {
    sessionId: session.id,
    rateable: false,
    rateableUntil: at(ratedAt + WINDOW),
    stars: 5,
    comment: null,
    ratedAt: at(ratedAt),
    changedAt: at(ratedAt),
  };
};

const REPORT_ID = "01a11300-0000-7000-8000-00000000d001";
const OPEN_ID = "01a11300-0000-7000-8000-00000000d002";
const CLOSED_ID = "01a11300-0000-7000-8000-00000000d003";

/** A report sent two hours ago, still waiting for us. */
export const disputeOpen = (session: data.Session): Dispute => ({
  id: OPEN_ID,
  sessionId: session.id,
  reason: "failed",
  details: "It stopped after 20 minutes with no error in the log.",
  state: "open",
  createdAt: at(data.NOW.getTime() - 2 * HOUR),
});

/** Ravi's report of his fine-tune, answered with a refund of `REPORT_REFUND`, two days ago. */
export const disputeRefunded: Dispute = {
  id: REPORT_ID,
  sessionId: data.reportedSession.id,
  reason: "wrong_output",
  details: "The adapter file was empty.",
  state: "refunded",
  createdAt: at(endedAt(data.reportedSession) + HOUR),
  resolutionReason:
    "The adapter was empty because the job stopped early on our host. Part of the cost is back.",
  resolvedAt: at(endedAt(data.reportedSession) + 3 * HOUR),
  refundPaise: data.REPORT_REFUND,
};

/** A report of a session that ran as it should, closed without a refund. */
export const disputeClosed: Dispute = {
  id: CLOSED_ID,
  sessionId: data.unreportedSession.id,
  reason: "slow",
  details: null,
  state: "not_refunded",
  createdAt: at(endedAt(data.unreportedSession) + 30 * MINUTE),
  resolutionReason: "It ran at the speed of its GPU. We found nothing wrong.",
  resolvedAt: at(endedAt(data.unreportedSession) + 2 * HOUR),
  refundPaise: null,
};

/** A page of Ravi's reports, newest first, as GET /v1/disputes serves it. */
export const disputesPage = (disputes: Dispute[] = [disputeClosed, disputeRefunded]) => ({
  disputes: [...disputes].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
  nextCursor: null,
});

/** What the report's session cost, and who kept what: 20% of each charge is the commission (ADR 0017). */
const COMMISSION_PERCENT = 20;
const charged = data.reportedSession.chargedPaise + data.REPORT_REFUND;
const hostKept = Math.floor((charged * (100 - COMMISSION_PERCENT)) / 100);
const platformKept = charged - hostKept;

/** The refund comes out of the platform's commission first, then the host's share. */
const fromCommission = Math.min(data.REPORT_REFUND, platformKept);
const fromHost = data.REPORT_REFUND - fromCommission;
export const REFUND_SPLIT = { fromCommission, fromHost, charged, hostKept, platformKept };

const HOST = "01a11300-0000-7000-8000-0000000000aa";

type AdminDispute = Schemas["AdminDispute"];
type AdminSummary = Schemas["AdminDisputeSummary"];

const adminSummary = (dispute: Dispute, renterId: string): AdminSummary => ({
  id: dispute.id,
  sessionId: dispute.sessionId,
  renterId,
  reason: dispute.reason,
  details: dispute.details ?? null,
  state: dispute.state,
  createdAt: dispute.createdAt,
  resolutionReason: dispute.resolutionReason ?? null,
  resolvedBy: dispute.state === "open" ? null : "admin nikki@example.com",
  resolvedAt: dispute.resolvedAt ?? null,
  refundPaise: dispute.refundPaise ?? null,
  commissionRefundPaise: dispute.refundPaise == null ? null : fromCommission,
  hostRefundPaise: dispute.refundPaise == null ? null : fromHost,
  refundTxn: dispute.refundPaise == null ? null : "01a11300-0000-7000-8000-00000000e001",
});

export const RAVI = "01a11300-0000-7000-8000-00000000000b";

/** The queue: Asha's open report first (oldest of the open), then the resolved ones. */
export const adminDisputes = (): Schemas["AdminDisputePage"] => ({
  disputes: [
    adminSummary(disputeOpen(data.failed), "01a11300-0000-7000-8000-00000000000a"),
    adminSummary(disputeRefunded, RAVI),
    adminSummary(disputeClosed, RAVI),
  ],
  nextCursor: null,
});

/** A report as an admin reads it: the session, what it cost now, who kept what, and its rating. */
export const adminDispute = (dispute: Dispute, renterId: string, session: data.Session): AdminDispute => {
  // The session's charged figure is net of what a report gave back, so the charge itself is the two together.
  const refundedAlready = dispute.refundPaise ?? 0;
  const total = session.chargedPaise + refundedAlready;
  const host = Math.floor((total * (100 - COMMISSION_PERCENT)) / 100);
  const platform = total - host;
  // A refund came off the commission first, then the host's share.
  const commissionLeft = Math.max(0, platform - refundedAlready);
  const hostLeft = host - Math.max(0, refundedAlready - platform);
  return {
    dispute: adminSummary(dispute, renterId),
    session: {
      state: session.state,
      hostId: session.startedAt ? HOST : null,
      startedAt: session.startedAt ?? null,
      endedAt: session.endedAt!,
      paidPaise: commissionLeft + hostLeft,
      commissionPaise: commissionLeft,
      hosts: session.startedAt ? [{ hostId: HOST, paise: hostLeft }] : [],
    },
    rating:
      dispute.id === REPORT_ID
        ? {
            stars: 2,
            comment: "The adapter file was empty and I paid for the whole run.",
            ratedAt: at(endedAt(session) + 2 * HOUR),
          }
        : null,
    hostFlags: [],
  };
};

const SETTLEMENT = "01a11300-0000-7000-8000-00000000e002";

/**
 * The minutes of a session as both witnesses saw them, each charged at its price in one settlement: as many as its
 * charge (less what a report gave back, `refundedPaise`) pays for. With `agentOnlyExtra` one more minute follows that
 * only the agent reported, so the lease didn't cover it and it wasn't charged, and a person may look at it.
 */
export const adminUsage = (
  session: data.Session,
  { refundedPaise = 0, agentOnlyExtra = false } = {},
): Schemas["AdminUsage"] => {
  const started = new Date(session.startedAt!).getTime();
  const price = session.pricePaisePerMin ?? 0;
  const amount = session.chargedPaise + refundedPaise;
  const billed = amount / price;
  const hostShare = Math.floor((amount * (100 - COMMISSION_PERCENT)) / 100);
  return {
    sessionId: session.id,
    minutes: Array.from({ length: billed + (agentOnlyExtra ? 1 : 0) }, (_, index) => {
      const extra = index >= billed;
      return {
        minuteIndex: index,
        minuteAt: at(started + index * MINUTE),
        agentReported: true,
        agentAttempt: 1,
        gpuSeconds: 60,
        avgUtilPct: 94,
        leaseChecked: true,
        leaseAlive: !extra,
        leaseAttempt: extra ? null : 1,
        billed: !extra,
        settlementTxn: extra ? null : SETTLEMENT,
        waived: false,
        refundTxn: null,
      };
    }),
    settlements: [
      {
        txnId: SETTLEMENT,
        windowNumber: 1,
        windowEnd: null,
        minutes: billed,
        amountPaise: amount,
        hostSharePaise: hostShare,
        commissionPaise: amount - hostShare,
        settledAt: at(endedAt(session) + 20_000),
      },
    ],
    waivers: [],
  };
};

type AdminSession = Schemas["AdminSession"];

/** A finished session as the console's session record has it: its timeline, its one attempt and what it was charged. */
export const adminSessionOf = (session: data.Session, renterId: string): AdminSession => ({
  session: {
    id: session.id,
    renterId,
    hostId: session.startedAt ? HOST : null,
    templateId: session.templateId,
    state: session.state,
    attempt: session.attempt,
    pricePaisePerMin: session.pricePaisePerMin ?? null,
    maxPricePaisePerMin: session.maxPricePaisePerMin,
    maxSpendPaise: session.maxSpendPaise,
    createdAt: session.createdAt,
    startedAt: session.startedAt ?? null,
    stateChangedAt: session.endedAt!,
    errorCode: session.errorCode ?? null,
  },
  timeline: session.events.map((event, index, all) => ({
    from: index === 0 ? null : all[index - 1]!.state,
    to: event.state,
    reason: event.reason,
    at: event.at,
  })),
  attempts: session.startedAt
    ? [
        {
          attempt: 1,
          hostId: HOST,
          gpuUuids: ["GPU-11111111-2222-3333-4444-555555555555"],
          pricePaisePerMin: session.pricePaisePerMin ?? 0,
          createdAt: session.createdAt,
          runningAt: session.startedAt,
          endedAt: session.endedAt!,
          stopRequestedAt: null,
          stopReason: null,
          latestLogSeq: 6,
        },
      ]
    : [],
  chargedPaise: session.chargedPaise,
  accruedPaise: 0,
  settled: true,
});
