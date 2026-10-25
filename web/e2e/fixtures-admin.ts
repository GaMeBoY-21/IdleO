import { NOW } from "./fixtures";

/**
 * Invented data for the admin console's screens (ADR 0027). Every figure is derived from the records here, so the
 * screens never show numbers that couldn't happen: a session's charge is its minutes at its price, a grant's balance
 * is what was granted, and the dashboard counts these records.
 */
const ago = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();
const later = (days: number) => new Date(NOW.getTime() + days * 86_400_000).toISOString();

export const ADMIN_ID = "01a1340a-0000-7000-8000-00000000ad01";
export const RENTER_ID = "01a1340a-0000-7000-8000-000000000a5a";
export const OWNER_ID = "01a1340a-0000-7000-8000-0000000000e1";
export const HOST_ID = "01a1340a-0000-7000-8000-00000000b001";
export const PENDING_HOST_ID = "01a1340a-0000-7000-8000-00000000b002";
export const SESSION_ID = "01a1340a-0000-7000-8000-00000000c001";
export const GRANT_ID = "01a1340a-0000-7000-8000-00000000d001";

/** Where the admin stands: an admin who hasn't set up, one who has but has no session, and one inside. */
export const notEnrolled = { enrolled: false, active: false, expiresAt: null };
export const enrolled = { enrolled: true, active: false, expiresAt: null };
export const inside = { enrolled: true, active: true, expiresAt: later(1 / 48) };

/** RFC 6238's test secret ("12345678901234567890"), as an enrolment shows it. */
export const enrolment = {
  secret: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
  otpauthUri:
    "otpauth://totp/IdleO:asha%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=IdleO&algorithm=SHA1&digits=6&period=30",
};

export const recoveryCodes = [
  "K7Q2M-9XD4P",
  "B3RTW-8HJ2N",
  "Z9C4V-6LM2Q",
  "P2X8D-4KT7R",
  "H6N3G-2WQ9S",
  "M4T7B-9JD3X",
  "R8L2C-5PV6Z",
  "W3K9F-7HN2D",
  "D5Q8J-3MX4T",
  "G2V6S-8RB9K",
];

export const confirmed = { token: "admin-session-token", expiresAt: later(1 / 48), recoveryCodes };

const PRICE = 3;
const MINUTES = 41;

export const renter = {
  id: RENTER_ID,
  email: "ravi@example.com",
  displayName: "Ravi",
  status: "active",
  createdAt: ago(9 * 24 * 60),
  lastSignInAt: ago(12),
  admin: false,
  apiKeys: 1,
  inviteNote: "Pune pilot students",
};

export const admin = {
  ...renter,
  id: ADMIN_ID,
  email: "asha@example.com",
  displayName: "Asha",
  admin: true,
  apiKeys: 0,
  inviteNote: null,
};

export const users = { users: [admin, renter], nextCursor: null };

export const grant = {
  id: GRANT_ID,
  userId: RENTER_ID,
  amountPaise: 50_000,
  reason: "Pilot welcome credit",
  grantedBy: "nikki",
  txnId: "01a1340a-0000-7000-8000-00000000e001",
  createdAt: ago(9 * 24 * 60),
  reversalId: null,
  reversedBy: null,
  reversedAt: null,
  walletBalancePaise: null,
};

export const grants = { grants: [grant] };

const gpu = {
  uuid: "GPU-6f1c2a9e-0d4b-4c8a-9e21-7b3f5a1d0c42",
  model: "RTX 4060 Laptop",
  vramMb: 8188,
  driverVersion: "566.14",
  firstSeenAt: ago(3 * 24 * 60),
  lastReportedAt: ago(0),
  goneAt: null,
};

const reading = { gpuUuid: gpu.uuid, tempC: 71, utilPct: 94, vramUsedMb: 6120 };

export const host = {
  id: HOST_ID,
  ownerId: OWNER_ID,
  name: "Dev's gaming laptop",
  status: "active",
  online: true,
  connectedSince: ago(95),
  agentVersion: "0.1.0",
  pricePaisePerMin: 12,
  keepFreeGb: 20,
  pausedAt: null,
  pausedUntil: null,
  createdAt: ago(3 * 24 * 60),
  lastSeenAt: ago(0),
  removedAt: null,
  gpus: [gpu],
  certificates: [
    {
      serial: "4f2a9c1e7b3d5a60",
      notBefore: ago(3 * 24 * 60),
      notAfter: later(27),
      revokedAt: null,
      createdAt: ago(3 * 24 * 60),
    },
  ],
  statusHistory: [
    {
      from: "pending",
      to: "active",
      reason: "Passed its checks",
      changedBy: "nikki",
      at: ago(3 * 24 * 60 - 30),
    },
  ],
  telemetry: { now: { at: ago(0), gpus: [reading] }, lastHour: [] },
  selfTest: {
    passed: true,
    distroVersion: "0.1.0",
    agentVersion: "0.1.0",
    checks: [
      { name: "distro_registered", passed: true, detail: "IdleO-0.1.0, WSL 2", tookMs: 410 },
      {
        name: "gpu_in_container",
        passed: true,
        detail: "NVIDIA GeForce RTX 4060 Laptop GPU, 8188 MiB",
        tookMs: 2950,
      },
      { name: "lockdown", passed: true, detail: "verify-lockdown.sh passed", tookMs: 1830 },
      { name: "storage_speed", passed: true, detail: "up 41 Mbit/s, down 88 Mbit/s", tookMs: 9600 },
    ],
    reportedAt: ago(3 * 24 * 60 - 40),
  } as {
    passed: boolean;
    distroVersion: string;
    agentVersion: string | null;
    checks: { name: string; passed: boolean; detail: string; tookMs: number }[];
    reportedAt: string;
  } | null,
  lastUpdate: null as { version: string; outcome: string; detail: string; reportedAt: string } | null,
  // The host score in numbers: what the scheduler ranks it by (docs/plan/execution-plan.md "Host score").
  score: {
    reliability: 0.812,
    bandwidth: 0.74,
    sessionScore: 0.861,
    selfTestScore: 0.667,
    penalty: 0.1,
    sessionsFinished: 14,
    sessionsLost: 1,
    selfTestsPassed: 3,
    selfTestsFailed: 0,
    reportsUpheld: 1,
    flags: 0,
    speedKbps: 64_000,
    label: "good",
    computedAt: ago(250),
  } as {
    reliability: number;
    bandwidth: number;
    sessionScore: number;
    selfTestScore: number;
    penalty: number;
    sessionsFinished: number;
    sessionsLost: number;
    selfTestsPassed: number;
    selfTestsFailed: number;
    reportsUpheld: number;
    flags: number;
    speedKbps: number | null;
    label: string;
    computedAt: string;
  } | null,
};

/** GET /admin/v1/hosts/{id}/self-test: nothing run from the web app yet, and the button is on. */
export const hostSelfTest = { run: null, canRun: true, whyNot: null, usedToday: 0, perDay: 6, retryAt: null };

export const pendingHost = {
  ...host,
  id: PENDING_HOST_ID,
  name: "Meera's desktop",
  status: "pending",
  online: true,
  pricePaisePerMin: 10,
  createdAt: ago(40),
  statusHistory: [],
  score: null,
  selfTest: {
    passed: false,
    distroVersion: "0.1.0",
    agentVersion: "0.1.0",
    checks: [
      { name: "distro_registered", passed: true, detail: "IdleO-0.1.0, WSL 2", tookMs: 380 },
      { name: "gpu_visible", passed: false, detail: "nvidia-smi found no GPU", tookMs: 640 },
    ],
    reportedAt: ago(35),
  },
};

const summary = (of: typeof host) => ({
  id: of.id,
  ownerId: of.ownerId,
  name: of.name,
  status: of.status,
  online: of.online,
  gpuModels: of.gpus.map((each) => each.model),
  createdAt: of.createdAt,
  lastSeenAt: of.lastSeenAt,
});

export const hosts = { hosts: [summary(pendingHost), summary(host)], nextCursor: null };

const startedAt = ago(MINUTES);

export const sessionSummary = {
  id: SESSION_ID,
  renterId: RENTER_ID,
  hostId: HOST_ID,
  templateId: "fine-tune-lora",
  state: "RUNNING",
  attempt: 1,
  pricePaisePerMin: PRICE,
  maxPricePaisePerMin: 5,
  maxSpendPaise: 20_000,
  createdAt: ago(MINUTES + 1),
  startedAt,
  stateChangedAt: startedAt,
  errorCode: null,
};

export const session = {
  session: sessionSummary,
  timeline: [
    { from: null, to: "REQUESTED", reason: "requested", at: ago(MINUTES + 1) },
    { from: "REQUESTED", to: "MATCHED", reason: "offer_accepted", at: ago(MINUTES + 1) },
    { from: "MATCHED", to: "PROVISIONING", reason: "start_sent", at: ago(MINUTES + 1) },
    { from: "PROVISIONING", to: "RUNNING", reason: "running", at: startedAt },
  ],
  attempts: [
    {
      attempt: 1,
      hostId: HOST_ID,
      gpuUuids: [gpu.uuid],
      pricePaisePerMin: PRICE,
      createdAt: ago(MINUTES + 1),
      runningAt: startedAt,
      endedAt: null,
      stopRequestedAt: null,
      stopReason: null,
      latestLogSeq: 12,
    },
  ],
  // Two settled windows of 15 minutes, and the rest accrued.
  chargedPaise: 30 * PRICE,
  accruedPaise: (MINUTES - 30) * PRICE,
  settled: false,
};

export const sessions = { sessions: [sessionSummary], nextCursor: null };

export const reviewMinutes = {
  minutes: [
    {
      sessionId: SESSION_ID,
      minuteIndex: 41,
      minuteAt: ago(1),
      renterId: RENTER_ID,
      attempt: 1,
      hostId: HOST_ID,
      pricePaisePerMin: PRICE,
      reason: "agent_only",
      queuedAt: ago(0),
      resolution: null as "charged" | "waived" | null,
      resolutionReason: null,
      resolvedBy: null,
      resolvedAt: null,
      settlementTxn: null,
    },
  ],
  nextCursor: null,
};

export const reconciliation = {
  runs: [
    {
      id: "01a1340a-0000-7000-8000-00000000f001",
      trigger: "nightly",
      finishedAt: ago(4 * 60),
      ledgerDebitsPaise: grant.amountPaise + session.chargedPaise,
      ledgerCreditsPaise: grant.amountPaise + session.chargedPaise,
      ledgerBalanced: true,
      mismatchCount: 0,
      mismatches: [],
    },
  ],
};

export const ledger = {
  entries: [
    {
      id: "01a1340a-0000-7000-8000-0000000e0002",
      txnId: grant.txnId,
      account: "renter_wallet",
      ownerId: RENTER_ID,
      amountPaise: grant.amountPaise,
      direction: "credit",
      refType: "credit_grant",
      refId: GRANT_ID,
      createdAt: grant.createdAt,
    },
  ],
  nextCursor: null,
};

export const invites = {
  invites: [
    {
      id: "01a1340a-0000-7000-8000-0000000a0001",
      note: "Pune pilot students",
      createdBy: "nikki",
      createdAt: ago(10 * 24 * 60),
      expiresAt: later(20),
      usedBy: RENTER_ID,
      usedByEmail: renter.email,
      usedAt: renter.createdAt,
    },
    {
      id: "01a1340a-0000-7000-8000-0000000a0002",
      note: "Pune pilot students",
      createdBy: "nikki",
      createdAt: ago(10 * 24 * 60),
      expiresAt: later(20),
      usedBy: null,
      usedByEmail: null,
      usedAt: null,
    },
  ],
  nextCursor: null,
};

export const audit = {
  entries: [
    {
      id: "01a1340a-0000-7000-8000-0000000b0002",
      actorKind: "admin",
      actorUserId: ADMIN_ID,
      actorName: admin.email,
      action: "host.activate",
      targetType: "host",
      targetId: HOST_ID,
      reason: "Passed its checks",
      before: { status: "pending" },
      after: { status: "active" },
      createdAt: host.statusHistory[0]!.at,
    },
    {
      id: "01a1340a-0000-7000-8000-0000000b0001",
      actorKind: "operator",
      actorUserId: null,
      actorName: "nikki",
      action: "credit.grant",
      targetType: "user",
      targetId: RENTER_ID,
      reason: grant.reason,
      before: { balancePaise: 0 },
      after: {
        id: GRANT_ID,
        txnId: grant.txnId,
        amountPaise: grant.amountPaise,
        balancePaise: grant.amountPaise,
      },
      createdAt: grant.createdAt,
    },
  ],
  nextCursor: null,
};

/** The setup app's installs over the last 30 days (host experience §6 row 11): one listed, one stuck at its driver. */
const installRows = [
  {
    installId: "01a14c00-0000-7000-8000-0000000000f1",
    openedAt: ago(60),
    minutes: 4.5,
    clicks: 3,
    stoppedAt: "listed",
    setupVersion: "0.2.0",
    windowsBuild: 26100,
    gpuModel: "NVIDIA GeForce RTX 4060 Laptop GPU",
    hostId: PENDING_HOST_ID,
    details: [],
  },
  {
    installId: "01a14c00-0000-7000-8000-0000000000f2",
    openedAt: ago(30),
    minutes: null,
    clicks: 2,
    stoppedAt: "checks failed: driver_too_old:531.79",
    setupVersion: "0.2.0",
    windowsBuild: 22631,
    gpuModel: "NVIDIA GeForce RTX 3060 Laptop GPU",
    hostId: null,
    details: [
      {
        step: "checks",
        reason: "driver_too_old:531.79",
        detail: "NVIDIA driver 531.79; this needs a newer one",
        at: ago(30),
      },
    ],
  },
];
const listedMinutes = installRows
  .flatMap((row) => (row.minutes === null ? [] : [row.minutes]))
  .sort((a, b) => a - b);

export const installs = {
  medianMinutes: listedMinutes.length ? listedMinutes[Math.floor(listedMinutes.length / 2)]! : null,
  opened: installRows.length,
  listed: listedMinutes.length,
  recent: installRows,
  failures: [
    {
      step: "checks",
      reason: "driver_too_old:531.79",
      count: installRows.filter((row) => row.stoppedAt.startsWith("checks failed")).length,
    },
  ],
};

/** The dashboard counts the records above: one host online and running one session, booked today. */
export const dashboard = {
  hostsOnline: [host, pendingHost].filter((each) => each.online).length,
  sessionsRunning: sessions.sessions.filter((each) => each.state === "RUNNING").length,
  sessionsToday: sessions.sessions.length,
  settlementBacklog: 0,
  reconciliationMismatches: reconciliation.runs[0]!.mismatchCount,
  reviewQueue: reviewMinutes.minutes.filter((each) => each.resolution === null).length,
  at: NOW.toISOString(),
};

/**
 * The abuse reviews (ADR 0038): one open, from a stopped session whose renter was suspended, with the signals the runner
 * sent and the job's last minutes; one confirmed and banned; one cleared with its charge refunded. The behaviour is
 * minutes of a job that works the GPU hard and sends little.
 */
export const ABUSE_OPEN_ID = "01a1340a-0000-7000-8000-00000000e001";
export const ABUSE_BANNED_ID = "01a1340a-0000-7000-8000-00000000e002";
export const ABUSE_CLEARED_ID = "01a1340a-0000-7000-8000-00000000e003";

const abuseSummary = (
  id: string,
  state: "open" | "confirmed" | "cleared",
  kinds: string[],
  action: "none" | "stopped" | "suspended",
  minutes: number,
  extra: { banned?: boolean; resolution?: string } = {},
) => ({
  id,
  sessionId: SESSION_ID,
  attempt: 1,
  renterId: RENTER_ID,
  hostId: HOST_ID,
  kinds,
  action,
  state,
  banned: extra.banned ?? false,
  createdAt: ago(minutes),
  resolvedAt: state === "open" ? null : ago(minutes / 2),
  resolution: extra.resolution ?? null,
});

const abuseOpen = abuseSummary(ABUSE_OPEN_ID, "open", ["pool_lookup", "mining_pattern"], "suspended", 90);

export const abuseQueue = {
  items: [
    abuseOpen,
    abuseSummary(ABUSE_BANNED_ID, "confirmed", ["process_hash"], "suspended", 600, {
      banned: true,
      resolution: "confirmed",
    }),
    abuseSummary(ABUSE_CLEARED_ID, "cleared", ["process_name"], "none", 1500, { resolution: "cleared" }),
  ],
  nextCursor: null,
};

export const abuseOpenOnly = { items: [abuseOpen], nextCursor: null };

const behaviourMinutes = Array.from({ length: 6 }, (_, index) => ({
  minuteIndex: index,
  gpuPercent: 99,
  memoryPercent: 12,
  pcieKbPerSecond: 40,
  networkOutBytes: 2_000 + index * 10,
  networkInBytes: 1_500,
  printedLines: 0,
  connectionsDropped: 0,
}));

export const abuseDetail = {
  item: abuseOpen,
  signals: [
    {
      kind: "pool_lookup",
      detail: "pool.example-miners.test",
      sha256: "",
      count: 1,
      stopped: false,
      at: ago(95),
    },
    {
      kind: "pool_lookup",
      detail: "pool.example-miners.test",
      sha256: "",
      count: 2,
      stopped: true,
      at: ago(91),
    },
    { kind: "mining_pattern", detail: "6 minutes", sha256: "", count: 0, stopped: false, at: ago(90) },
  ],
  behaviour: behaviourMinutes,
  notes: [{ at: ago(60), authorId: ADMIN_ID, note: "Renter's account is three days old." }],
  paidPaise: 30 * PRICE,
  refundedPaise: null,
};

/*
 * Money in (ADR 0039): one renter's ₹500 top-up by UPI, paid and receipted; ₹200 of it asked back; a chargeback on it
 * for ₹150, frozen whole because the credit was still there; and one disagreement the reconciliation found. Every
 * figure follows from the top-up.
 */
export const TOPUP_ID = "01a1340a-0000-7000-8000-000000071001";
export const RECEIPT_ID = "01a1340a-0000-7000-8000-000000072001";
export const REFUND_ID = "01a1340a-0000-7000-8000-000000073001";
export const CHARGEBACK_ID = "01a1340a-0000-7000-8000-000000074001";
export const MISMATCH_ID = "01a1340a-0000-7000-8000-000000075001";
const TOPUP_PAISE = 50_000;
const ASKED_BACK_PAISE = 20_000;
const DISPUTED_PAISE = 15_000;

export const payment = {
  id: TOPUP_ID,
  userId: RENTER_ID,
  amountPaise: TOPUP_PAISE,
  status: "paid",
  orderId: "order_Fk000000000101",
  paymentId: "pay_Fk000000000102",
  method: "upi",
  cardNetwork: null,
  cardLast4: null,
  failureReason: null,
  creditedBy: "checkout",
  invoiceId: RECEIPT_ID,
  invoiceNumber: "IDL2627-000042",
  createdAt: ago(3 * 24 * 60),
  paidAt: ago(3 * 24 * 60 - 1),
};

export const payments = { payments: [payment] };

export const paymentReconciliation = {
  runs: [
    {
      id: "01a1340a-0000-7000-8000-000000076001",
      day: ago(24 * 60).slice(0, 10),
      trigger: "nightly",
      startedAt: ago(5 * 60),
      finishedAt: ago(5 * 60 - 1),
      payments: 2,
      refunds: 0,
      found: 1,
      open: 1,
      failure: null,
    },
  ],
  mismatches: [
    {
      id: MISMATCH_ID,
      kind: "authorized_not_captured",
      subject: "pay_Fk000000000201",
      paymentId: "pay_Fk000000000201",
      topUpId: null,
      userId: RENTER_ID,
      ourPaise: 10_000,
      theirPaise: 10_000,
      ourStatus: "pending",
      theirStatus: "authorized",
      detail:
        "Authorised and not captured: check auto-capture in Razorpay's dashboard. Nothing is credited for it.",
      state: "open",
      foundAt: ago(5 * 60),
      lastFoundAt: ago(5 * 60),
      closedAt: null,
      closedBy: null,
      closeReason: null,
    },
  ],
};

export const creditRefund = {
  id: REFUND_ID,
  userId: RENTER_ID,
  cause: "request",
  status: "requested",
  requestedPaise: ASKED_BACK_PAISE,
  approvedPaise: null,
  // What's left of the top-up once the session spent from it and the chargeback froze its part.
  refundablePaise: TOPUP_PAISE - session.chargedPaise - DISPUTED_PAISE,
  note: "I bought more than I need this month.",
  ticketNumber: 17,
  ticketId: "01a1340a-0000-7000-8000-000000077001",
  decidedBy: null,
  decision: null,
  requestedAt: ago(26 * 60),
  finishedAt: null,
  parts: [],
};

export const creditRefunds = { refunds: [creditRefund] };

export const chargeback = {
  id: CHARGEBACK_ID,
  disputeId: "disp_Fk000000000301",
  paymentId: payment.paymentId,
  userId: RENTER_ID,
  amountPaise: DISPUTED_PAISE,
  currency: "INR",
  reasonCode: "goods_or_services_not_received_or_partially_received",
  phase: "chargeback",
  status: "open",
  respondBy: later(1),
  deductedPaise: 0,
  frozenPaise: DISPUTED_PAISE,
  lostPaise: null,
  releasedPaise: null,
  outcome: null,
  openedAt: ago(2 * 60),
  settledAt: null,
  payment,
  sessions: [{ sessionId: SESSION_ID, paidFromThisPaymentPaise: session.chargedPaise }],
};

export const chargebacks = { chargebacks: [chargeback] };

export const receipt = {
  userId: RENTER_ID,
  invoice: {
    id: RECEIPT_ID,
    number: payment.invoiceNumber,
    kind: "receipt",
    title: "Receipt",
    issuedAt: payment.paidAt,
    taxablePaise: TOPUP_PAISE,
    taxPaise: 0,
    totalPaise: TOPUP_PAISE,
    topUpId: TOPUP_ID,
    creditNoteFor: null,
  },
};

export const invoices = { invoices: [receipt] };

// Payouts (ADR 0040): a run, its payouts (one processed, one that failed), the balance against the next run, a PAN and
// a UPI ID waiting for an admin, a clawback still owed, and the payout reconciliation with one item open. A payout's
// net is its gross less its tax.
export const PAYOUT_FAILED_ID = "01a1340a-0000-7000-8000-000000081002";
export const PAYOUT_MISMATCH_ID = "01a1340a-0000-7000-8000-000000082001";
export const CLAWBACK_ID = "01a1340a-0000-7000-8000-000000083001";
export const UPI_REVIEW_ID = "01a1340a-0000-7000-8000-000000084001";

const adminPayout = (
  id: string,
  gross: number,
  tds: number,
  status: string,
  extra: Record<string, unknown> = {},
) => ({
  id,
  userId: OWNER_ID,
  runId: "01a1340a-0000-7000-8000-000000080001",
  kind: "weekly",
  grossPaise: gross,
  tdsPaise: tds,
  tdsSection: "194-O",
  feePaise: 0,
  netPaise: gross - tds,
  status,
  razorpayxId: `pout_${id.slice(-14).replaceAll("-", "")}`,
  utr: status === "processed" ? "523223155921" : null,
  statusReason: null,
  statusDescription: null,
  attempts: 1,
  lastError: null,
  createdAt: ago(3 * 24 * 60),
  processedAt: status === "processed" ? ago(3 * 24 * 60 - 2) : null,
  endedAt: null,
  ...extra,
});

export const adminPayouts = {
  runs: [
    {
      id: "01a1340a-0000-7000-8000-000000080001",
      kind: "weekly",
      payday: ago(3 * 24 * 60).slice(0, 10),
      trigger: "weekly",
      startedAt: ago(3 * 24 * 60),
      finishedAt: ago(3 * 24 * 60 - 1),
      owners: 2,
      payouts: 2,
      totalPaise: 12_800 + 9_600,
      failure: null,
    },
  ],
  payouts: [
    adminPayout("01a1340a-0000-7000-8000-000000081001", 12_800, 0, "processed"),
    adminPayout(PAYOUT_FAILED_ID, 9_600, 0, "failed", {
      statusReason: "bank_account_closed",
      statusDescription:
        "Payout failed as the beneficiary account is closed. Please contact the beneficiary bank",
    }),
  ],
  nextCursor: null,
  balance: {
    payday: later(3).slice(0, 10),
    checkedAt: ago(10),
    balancePaise: 5_000,
    duePaise: 12_800,
    isShort: true,
    failure: null,
  },
  nextPayday: later(3),
};

export const payoutVerifications = {
  pans: [
    {
      userId: OWNER_ID,
      last4: "234F",
      name: "Asha Rao",
      holder: "individual",
      state: "review",
      checked: "not_checked",
      verifier: "manual",
      submittedAt: ago(60),
      reason: null,
    },
  ],
  upiIds: [
    {
      id: UPI_REVIEW_ID,
      userId: RENTER_ID,
      vpa: "ravi.k@okaxis",
      registeredName: "R K SHARMA",
      panName: "Ravi Kumar Sharma",
      nameMatchScore: 42,
      state: "review",
      checkedAt: ago(30),
      confirmedAt: ago(29),
    },
  ],
};

export const clawbacks = {
  clawbacks: [
    {
      id: CLAWBACK_ID,
      ownerId: OWNER_ID,
      hostId: HOST_ID,
      sessionId: SESSION_ID,
      cause: "report_refund",
      amountPaise: 800,
      fromBalancePaise: 300,
      owedPaise: 500,
      recoveredPaise: 0,
      waivedPaise: 0,
      outstandingPaise: 500,
      state: "owed",
      createdAt: ago(120),
      settledAt: null,
      waivedBy: null,
      waiveReason: null,
    },
  ],
  nextCursor: null,
};

export const payoutReconciliation = {
  runs: [
    {
      id: "01a1340a-0000-7000-8000-000000085001",
      day: ago(24 * 60).slice(0, 10),
      trigger: "nightly",
      startedAt: ago(5 * 60),
      finishedAt: ago(5 * 60 - 1),
      payouts: 2,
      found: 1,
      open: 1,
      failure: null,
    },
  ],
  mismatches: [
    {
      id: PAYOUT_MISMATCH_ID,
      kind: "missing_ours",
      subject: "pout_Fx000000000099",
      payoutId: null,
      userId: null,
      ourPaise: null,
      theirPaise: 12_345,
      ourStatus: null,
      theirStatus: "processed",
      detail: "RazorpayX has payout pout_Fx000000000099 of 12345 paise that isn't one of ours.",
      state: "open",
      foundAt: ago(5 * 60),
      lastFoundAt: ago(5 * 60),
      closedAt: null,
      closedBy: null,
      closeReason: null,
    },
  ],
};
