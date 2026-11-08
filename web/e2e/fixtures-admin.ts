import type { components } from "../src/api/schema.gen";
import { NOW } from "./fixtures";

type Schemas = components["schemas"];

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
/** Every permission there is: the Owner's (control-plane/src/test/resources/admin-permission-matrix.csv). */
export const EVERY_PERMISSION = [
  "abuse.blocklist",
  "abuse.clear",
  "abuse.decide",
  "admins.manage",
  "approvals.decide",
  "approvals.read",
  "abuse.read",
  "audit.read",
  "clawbacks.read",
  "clawbacks.waive",
  "ops.dashboard.read",
  "disputes.decide",
  "disputes.read",
  "disputes.refund",
  "export.create",
  "flags.change",
  "flags.read",
  "fleet.read",
  "rollouts.pause",
  "rollouts.widen",
  "broadcasts.send",
  "hosts.activate",
  "hosts.certificates.revoke",
  "hosts.read",
  "hosts.self-test",
  "hosts.suspend",
  "images.block",
  "images.read",
  "images.unblock",
  "invites.create",
  "invites.read",
  "invoices.credit-note",
  "invoices.pdf.read",
  "invoices.read",
  "ledger.read",
  "money.adjust",
  "money.read",
  "periods.close",
  "tax.export",
  "recon.read",
  "recon.resolve",
  "notes.read",
  "notes.write",
  "notices.publish",
  "payments.read",
  "payouts.read",
  "payouts.release",
  "payouts.run",
  "privacy.export",
  "privacy.legal-order",
  "privacy.read",
  "ratings.read",
  "reconciliation.accept",
  "reconciliation.run",
  "refunds.approve",
  "refunds.decline",
  "refunds.read",
  "review.charge",
  "review.read",
  "review.waive",
  "sessions.log.read",
  "sessions.read",
  "sessions.stop",
  "tags.manage",
  "tickets.read",
  "tickets.reply",
  "trust.read",
  "users.pii.reveal",
  "users.read",
  "users.suspend",
  "users.unsuspend",
  "queues.read",
  "queues.claim",
  "queues.assign",
  "queues.sla",
  "queues.bulk",
  "macros.manage",
  "enforcement.read",
  "enforcement.apply",
  "enforcement.lift",
  "appeals.decide",
  "verifications.decide",
  "verifications.read",
];
export const notEnrolled = {
  enrolled: false,
  active: false,
  expiresAt: null,
  roles: [],
  permissions: [],
  passkeys: 0,
};
export const enrolled = {
  enrolled: true,
  active: false,
  expiresAt: null,
  roles: [],
  permissions: [],
  passkeys: 0,
};
export const inside = {
  enrolled: true,
  active: true,
  expiresAt: later(1 / 48),
  roles: ["Owner"],
  permissions: EVERY_PERMISSION,
  passkeys: 0,
};

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
  suspensionCause: null as string | null,
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
  presence: "ONLINE" as const,
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
  // The host score in numbers: what the scheduler ranks it by.
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
  presence: "ONLINE" as const,
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
  presence: of.presence,
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
      checks: [
        { position: 1, name: "each transaction balances", found: 0 },
        { position: 2, name: "all debits equal all credits", found: 0 },
        { position: 3, name: "no wallet below zero or holding more than it has", found: 0 },
        { position: 4, name: "every wallet equals its ledger entries", found: 0 },
        { position: 5, name: "every wallet's reserved amount equals its active holds", found: 0 },
        { position: 6, name: "every hold equals its recorded changes", found: 0 },
        { position: 7, name: "no host's earnings below zero", found: 0 },
        { position: 8, name: "every credit lot equals its entries and none is below zero", found: 0 },
        { position: 9, name: "credit from before lots nets to zero", found: 0 },
        { position: 10, name: "nothing frozen or refunding below zero", found: 0 },
        { position: 11, name: "every paid top-up credited exactly once", found: 0 },
        { position: 12, name: "every processed refund part debited exactly once", found: 0 },
        { position: 13, name: "no payout in flight below zero", found: 0 },
        { position: 14, name: "paid equals the sum of processed payouts", found: 0 },
        { position: 15, name: "a receivable is only recovered, never paid out", found: 0 },
        { position: 16, name: "no session charged past its spending cap", found: 0 },
        { position: 17, name: "every billed minute had both witnesses", found: 0 },
      ],
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

/** The setup app's installs over the last 30 days: one listed, one stuck at its driver. */
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

/**
 * The control room's rollups (ADR 0056): the records above in the marketplace (one host online, running one session
 * booked today), a little money, the funnels, and every check fine but the review queue's minutes waiting.
 */
export const controlRoom = {
  money: {
    today: { gmvPaise: 12_000, takePaise: 2_400, refundsPaise: 0, chargebacksPaise: 0 },
    week: { gmvPaise: 84_000, takePaise: 16_800, refundsPaise: 5_000, chargebacksPaise: 0 },
    month: { gmvPaise: 310_000, takePaise: 62_000, refundsPaise: 5_000, chargebacksPaise: 5_000 },
  },
  marketplace: {
    sessionsRunning: sessions.sessions.filter((each) => each.state === "RUNNING").length,
    bookingsWaiting: 0,
    bookingsToday: sessions.sessions.length,
    matchP50Seconds: 2.4,
    matchP95Seconds: 9.8,
    startsAttempted: 12,
    startsSucceeded: 11,
    failures: [{ reason: "lease_lapsed", count: 1 }],
    busyGpuHours: [{ model: "NVIDIA GeForce RTX 4080 Laptop GPU", value: 6 }],
    demand: [
      { model: "NVIDIA GeForce RTX 4080 Laptop GPU", hour: 20, value: 1.5 },
      { model: "NVIDIA GeForce RTX 4080 Laptop GPU", hour: 21, value: 2 },
    ],
  },
  supply: {
    online: [
      {
        model: "NVIDIA GeForce RTX 4080 Laptop GPU",
        value: [host, pendingHost].filter((each) => each.online).length,
      },
    ],
    onlineGpuHours: [{ model: "NVIDIA GeForce RTX 4080 Laptop GPU", value: 24 }],
    supply: [
      { model: "NVIDIA GeForce RTX 4080 Laptop GPU", hour: 20, value: 1 },
      { model: "NVIDIA GeForce RTX 4080 Laptop GPU", hour: 21, value: 1 },
    ],
  },
  utilisation: [{ model: "NVIDIA GeForce RTX 4080 Laptop GPU", value: 0.25 }],
  funnels: [
    { funnel: "hosts", order: 1, step: "linked a machine", people: 4 },
    { funnel: "hosts", order: 2, step: "self-test passed", people: 3 },
    { funnel: "hosts", order: 3, step: "first session", people: 2 },
    { funnel: "hosts", order: 4, step: "first payout", people: 1 },
    { funnel: "renters", order: 1, step: "signed up", people: 120 },
    { funnel: "renters", order: 2, step: "first top-up", people: 40 },
    { funnel: "renters", order: 3, step: "first session", people: 30 },
    { funnel: "renters", order: 4, step: "second session", people: 18 },
  ],
  demand: [
    { gpu: "12 GB", classMb: 12_000, hour: 14, asked: 9, served: 5, gap: 3 },
    { gpu: "12 GB", classMb: 12_000, hour: 20, asked: 4, served: 4, gap: 0 },
    { gpu: "8 GB", classMb: 8_000, hour: 20, asked: 6, served: 6, gap: 0 },
    { gpu: "24 GB", classMb: 24_000, hour: 21, asked: 2, served: 0, gap: 2 },
  ],
  cohorts: [
    { funnel: "hosts", week: "2026-09-28", signedUp: 40, order: 1, step: "linked a machine", within7: 3, within30: 4, whole7: true, whole30: true },
    { funnel: "hosts", week: "2026-09-28", signedUp: 40, order: 3, step: "first session", within7: 1, within30: 2, whole7: true, whole30: true },
    { funnel: "renters", week: "2026-09-28", signedUp: 40, order: 2, step: "first top-up", within7: 10, within30: 14, whole7: true, whole30: true },
    { funnel: "renters", week: "2026-09-28", signedUp: 40, order: 3, step: "first session", within7: 8, within30: 12, whole7: true, whole30: true },
    { funnel: "renters", week: "2026-10-05", signedUp: 25, order: 2, step: "first top-up", within7: 5, within30: 6, whole7: true, whole30: false },
    { funnel: "renters", week: "2026-10-05", signedUp: 25, order: 3, step: "first session", within7: 2, within30: 3, whole7: true, whole30: false },
  ],
  health: [
    { name: "database", ok: true, detail: "answered in 2 ms" },
    { name: "valkey", ok: true, detail: "answered PONG" },
    { name: "backup age", ok: true, detail: "the newest full backup is 5 hours old" },
    { name: "agent streams", ok: true, detail: "1 agent connected" },
    { name: "scanner", ok: true, detail: "no check waiting" },
    { name: "settlement reconciliation", ok: true, detail: "every settlement agrees with the ledger" },
    { name: "wallet reconciliation", ok: true, detail: "the latest run found nothing wrong" },
    { name: "settlement backlog", ok: true, detail: "every session settled on time" },
    {
      name: "review queue",
      ok: true,
      detail: `${reviewMinutes.minutes.filter((each) => each.resolution === null).length} minutes waiting for a decision`,
    },
    { name: "inbox targets", ok: false, detail: "1 item past its target" },
  ],
  computedAt: {
    money: NOW.toISOString(),
    marketplace: NOW.toISOString(),
    supply: NOW.toISOString(),
    funnels: NOW.toISOString(),
    demand: NOW.toISOString(),
    cohorts: NOW.toISOString(),
    health: NOW.toISOString(),
  },
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
 * A confirmed review whose runner flagged a program by name and worked out its build (ADR 0038, ADR 0041): the admin can add
 * that build to the blocklist. And one already waiting, which the published list does not name yet, and one it does.
 */
export const ABUSE_LEARNABLE_ID = "01a1340a-0000-7000-8000-00000000e004";
export const LEARNABLE_BUILD = "b".repeat(64);
const abuseLearnable = abuseSummary(ABUSE_LEARNABLE_ID, "confirmed", ["process_name"], "none", 300, {
  resolution: "It really was a miner.",
});
export const abuseLearnableDetail = {
  item: abuseLearnable,
  signals: [
    {
      kind: "process_name",
      detail: "renamedminer",
      sha256: LEARNABLE_BUILD,
      count: 0,
      stopped: false,
      at: ago(310),
    },
  ],
  behaviour: [],
  notes: [],
  paidPaise: 30 * PRICE,
  refundedPaise: null,
};
export const blocklistAdditions = {
  additions: [
    {
      id: "01a1340a-0000-7000-8000-00000000f001",
      itemId: "01a1340a-0000-7000-8000-00000000e00a",
      sha256: "a".repeat(64),
      name: "oldminer",
      kind: "miner",
      reason: "Seen mining on two hosts.",
      addedBy: ADMIN_ID,
      createdAt: ago(3 * 24 * 60),
      published: true,
    },
    {
      id: "01a1340a-0000-7000-8000-00000000f002",
      itemId: "01a1340a-0000-7000-8000-00000000e00b",
      sha256: "c".repeat(64),
      name: "cracker",
      kind: "password_cracker",
      reason: "A password cracker, run on a host's GPU.",
      addedBy: ADMIN_ID,
      createdAt: ago(60),
      published: false,
    },
  ],
};
export const noBlocklistAdditions = { additions: [] };

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

export const BATCH_ID = "01a1340a-0000-7000-8000-000000080002";

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
      state: "released",
      version: 3,
      releasedAt: ago(3 * 24 * 60 - 1),
      releasedBy: "admin Asha",
    },
    {
      id: BATCH_ID,
      kind: "manual",
      payday: ago(60).slice(0, 10),
      trigger: "admin Asha",
      startedAt: ago(60),
      finishedAt: ago(59),
      owners: 2,
      payouts: 1,
      totalPaise: 15_840,
      failure: null,
      state: "preview",
      version: 2,
      releasedAt: null,
      releasedBy: null,
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

/** GET /admin/v1/approvals: one waiting for a second admin. */
export const approvals: { requests: Schemas["AdminApproval"][]; nextCursor: null } = {
  requests: [
    {
      id: "01a1a7bf-93ce-725f-b2a8-656176c3d7c3",
      kind: "credit.grant",
      permission: "money.adjust",
      targetType: null,
      targetId: null,
      changes: [{ what: `free credit for user ${RENTER_ID}`, from: 0, to: 50000 }],
      requestedBy: "01a1a7bf-93ce-725f-b2a8-656176c3d7c4",
      reason: "goodwill credit after the outage",
      state: "pending",
      solo: false,
      runAfter: null,
      decidedBy: null,
      decisionReason: null,
      decidedAt: null,
      failure: null,
      downloadable: false,
      expiresAt: later(3),
      createdAt: later(0),
      version: 1,
    },
  ],
  nextCursor: null,
};

/** GET /admin/v1/passkeys. */
export const passkeys: { passkeys: Schemas["AdminPasskey"][] } = {
  passkeys: [
    {
      id: "01a1a7bf-93ce-725f-b2a8-656176c3d7c5",
      name: "My phone",
      createdAt: later(0),
      lastUsedAt: later(0),
    },
  ],
};

/** GET /admin/v1/me/sessions and /admin/v1/admins/sessions. */
export const mySessions: { sessions: Schemas["AdminSessionRecord"][] } = {
  sessions: [
    {
      id: "01a1a7bf-93ce-725f-b2a8-656176c3d7c6",
      userId: "01a1a7bf-93ce-725f-b2a8-656176c3d7c4",
      startedAt: later(0),
      lastUsedAt: later(0),
      expiresAt: later(1 / 48),
      endsBy: later(1 / 3),
      ip: "203.0.113.0/24",
      userAgent: "Mozilla/5.0 Chrome/140.0",
      current: true,
    },
  ],
};

/** GET /admin/v1/admins. */
export const adminAccounts: Schemas["AdminAccounts"] = {
  admins: [
    {
      id: "01a1a7bf-93ce-725f-b2a8-656176c3d7c4",
      email: "ni•••@example.com",
      enrolled: true,
      locked: false,
      roles: ["owner"],
      version: 1,
    },
  ],
  roles: [
    { id: "owner", name: "Owner", permissions: EVERY_PERMISSION },
    { id: "auditor", name: "Auditor", permissions: ["audit.read"] },
  ],
};

/** GET /admin/v1/audit/standing. */
export const auditStanding: Schemas["AuditStanding"] = {
  latestCheck: {
    rows: 1204,
    headSeq: 1204,
    intact: true,
    brokenSeq: null,
    detail: null,
    tookMs: 18,
    trigger: "daily",
    checkedAt: later(0),
  },
  copies: [],
};

/** GET /admin/v1/personal-data/access-log. */
export const accessLog: { entries: Schemas["AccessLogEntry"][]; nextCursor: null } = {
  entries: [
    {
      id: "01a1a7bf-93ce-725f-b2a8-656176c3d7c7",
      adminId: "01a1a7bf-93ce-725f-b2a8-656176c3d7c4",
      subjectUserId: RENTER_ID,
      field: "email",
      reason: "answering ticket 142",
      adminSessionId: "01a1a7bf-93ce-725f-b2a8-656176c3d7c6",
      at: later(0),
    },
  ],
  nextCursor: null,
};

/** The inbox: one on someone, one past its target, one paused. */
export const inbox: { items: Schemas["AdminQueueItem"][]; nextCursor: null } = {
  items: [
    {
      id: "01a1ac00-0000-7000-8000-000000000001",
      type: "ticket",
      title: "#1001 grievance",
      link: "/admin/tickets/?id=01a1ac00-0000-7000-8000-00000000aaaa",
      state: "claimed",
      assigneeId: "01a1ac00-0000-7000-8000-00000000bbbb",
      assigneeName: "Nikki",
      priority: "high",
      overridden: false,
      openedAt: "2026-11-01T10:00:00Z",
      dueAt: "2026-11-02T10:00:00Z",
      paused: false,
      moneyPaise: 0,
      runningSession: false,
      version: 2,
    },
    {
      id: "01a1ac00-0000-7000-8000-000000000002",
      type: "chargeback",
      title: "Chargeback disp_123",
      link: "/admin/chargebacks/?id=01a1ac00-0000-7000-8000-00000000cccc",
      state: "open",
      priority: "urgent",
      overridden: false,
      openedAt: "2026-11-01T10:00:00Z",
      dueAt: "2026-11-02T10:00:00Z",
      paused: false,
      breachedAt: "2026-11-02T10:00:01Z",
      moneyPaise: 100000,
      runningSession: false,
      version: 1,
    },
    {
      id: "01a1ac00-0000-7000-8000-000000000003",
      type: "ticket",
      title: "#1002 account",
      link: "/admin/tickets/?id=01a1ac00-0000-7000-8000-00000000dddd",
      state: "waiting",
      priority: "low",
      overridden: false,
      openedAt: "2026-11-01T10:00:00Z",
      paused: true,
      moneyPaise: 0,
      runningSession: false,
      version: 1,
    },
  ],
  nextCursor: null,
};

export const slaTargets: Schemas["AdminSlaTarget"][] = [
  { sla: "grievance", firstMinutes: 1440, resolveMinutes: 21600, pauses: false, legal: true, version: 1 },
  { sla: "ticket", firstMinutes: 1440, pauses: true, legal: false, version: 1 },
  { sla: "chargeback", pauses: false, legal: false, version: 1 },
];

const shape = (priority: string): Schemas["BulkShape"] => ({
  priority,
  state: "open",
  tagged: false,
});

export const bulkPreview: Schemas["BulkPreview"] = {
  changes: [
    {
      item: inbox.items[2]!.id,
      title: "#1002 account",
      before: shape("low"),
      after: shape("high"),
      applies: true,
    },
    {
      item: inbox.items[0]!.id,
      title: "#1001 grievance",
      before: shape("high"),
      after: shape("high"),
      applies: false,
      why: "It's at that priority already.",
    },
  ],
  willChange: 1,
  unchanged: 1,
};

export const enforcement: Schemas["AdminEnforcement"] = {
  id: "01a1ac00-0000-7000-8000-000000000010",
  userId: "01a1ac00-0000-7000-8000-000000000011",
  role: "renter",
  step: "suspend",
  template: "renter-suspend",
  title: "Your account is suspended",
  clause: "The Terms, on suspending accounts",
  endsAt: "2026-12-01T00:00:00Z",
  strikeExpiresAt: "2027-05-01T00:00:00Z",
  state: "active",
  reason: "It mined on someone else's machine",
  enforcedBy: "01a1ac00-0000-7000-8000-000000000012",
  createdAt: "2026-11-01T00:00:00Z",
  appealed: true,
  version: 1,
};

export const appeal: Schemas["AdminAppeal"] = {
  id: "01a1ac00-0000-7000-8000-000000000020",
  enforcementId: enforcement.id,
  userId: enforcement.userId,
  body: "It wasn't me: my brother used the laptop.",
  attachments: [],
  state: "open",
  sameReviewer: false,
  createdAt: "2026-11-02T00:00:00Z",
  version: 1,
};

// The ledger explorer, adjustments, months and money reports (ADR 0055): a grant and a session's charge in the chart of
// accounts, one fee correction approved and posted, last month ready to close, and a month of figures.
export const ADJUSTMENT_ID = "01a1340a-0000-7000-8000-0000000ad001";
const FEE_TXN = "01a1340a-0000-7000-8000-0000000ad002";
const HOST_SHARE = Math.round(session.chargedPaise * 0.8);
const TAKE = session.chargedPaise - HOST_SHARE;

export const ledgerAccounts = {
  at: ago(0),
  owner: null,
  accounts: [
    {
      account: "renter_wallet",
      type: "liability",
      owned: true,
      normalSide: "credit",
      description: "Free credit we owe renters service for: grants, promotions and give-backs, in lots.",
      debitsPaise: session.chargedPaise,
      creditsPaise: grant.amountPaise,
      balancePaise: grant.amountPaise - session.chargedPaise,
    },
    {
      account: "host_payable",
      type: "liability",
      owned: true,
      normalSide: "credit",
      description: "What we owe each machine's owner for its sessions, not paid yet.",
      debitsPaise: 0,
      creditsPaise: HOST_SHARE,
      balancePaise: HOST_SHARE,
    },
    {
      account: "platform_revenue",
      type: "income",
      owned: false,
      normalSide: "credit",
      description: "Our share of every session: the take.",
      debitsPaise: 0,
      creditsPaise: TAKE,
      balancePaise: TAKE,
    },
    {
      account: "promotions",
      type: "expense",
      owned: false,
      normalSide: "debit",
      description: "What the free credit we give away costs us.",
      debitsPaise: grant.amountPaise,
      creditsPaise: 0,
      balancePaise: grant.amountPaise,
    },
    {
      account: "gateway_fees",
      type: "expense",
      owned: false,
      normalSide: "debit",
      description: "Razorpay's fees on what it settles to us.",
      debitsPaise: 236,
      creditsPaise: 0,
      balancePaise: 236,
    },
    {
      account: "gateway_clearing",
      type: "asset",
      owned: false,
      normalSide: "debit",
      description: "Money Razorpay holds for us, until it settles to the bank.",
      debitsPaise: 0,
      creditsPaise: 236,
      balancePaise: -236,
    },
  ],
};

export const trialBalance = {
  at: ago(0),
  accounts: [
    {
      account: "renter_wallet",
      type: "liability",
      debitPaise: 0,
      creditPaise: grant.amountPaise - session.chargedPaise,
    },
    { account: "host_payable", type: "liability", debitPaise: 0, creditPaise: HOST_SHARE },
    { account: "platform_revenue", type: "income", debitPaise: 0, creditPaise: TAKE },
    { account: "promotions", type: "expense", debitPaise: grant.amountPaise, creditPaise: 0 },
    { account: "gateway_fees", type: "expense", debitPaise: 236, creditPaise: 0 },
    { account: "gateway_clearing", type: "asset", debitPaise: 0, creditPaise: 236 },
  ],
  debitsPaise: grant.amountPaise + 236,
  creditsPaise: grant.amountPaise + 236,
  balanced: true,
};

export const ledgerTransaction = {
  txnId: grant.txnId,
  source: { refType: "credit_grant", refId: GRANT_ID, page: "user", pageId: RENTER_ID },
  createdAt: grant.createdAt,
  legs: [
    {
      id: "01a1340a-0000-7000-8000-0000000e0001",
      account: "promotions",
      ownerId: null,
      amountPaise: grant.amountPaise,
      direction: "debit",
      lotId: null,
    },
    {
      id: "01a1340a-0000-7000-8000-0000000e0002",
      account: "renter_wallet",
      ownerId: RENTER_ID,
      amountPaise: grant.amountPaise,
      direction: "credit",
      lotId: "01a1340a-0000-7000-8000-0000000e0003",
    },
  ],
};

export const adjustments = {
  adjustments: [
    {
      id: ADJUSTMENT_ID,
      template: "fee_correction",
      reason: "Razorpay kept a fee on the 3 November settlement",
      reference: "settlement setl_Pq1",
      correctsMonth: null,
      reversesId: null,
      reversedById: null,
      approvalId: "01a1340a-0000-7000-8000-0000000ad003",
      txnId: FEE_TXN,
      requestedBy: "admin Asha",
      createdAt: ago(90),
      legs: [
        { account: "gateway_fees", ownerId: null, amountPaise: 236, direction: "debit" },
        { account: "gateway_clearing", ownerId: null, amountPaise: 236, direction: "credit" },
      ],
      version: 1,
    },
  ],
  nextCursor: null,
};

const DONE = [
  { name: "The ledger's checks pass", done: true, detail: "the latest run found nothing" },
  { name: "No open payment reconciliation items", done: true, detail: "none open" },
];

export const periods = {
  current: "2026-11",
  months: [
    {
      month: "2026-11",
      state: "open",
      over: false,
      checklist: [
        {
          name: "The ledger's checks pass",
          done: false,
          detail: "no run of the ledger's checks since the month ended; run them now",
        },
        { name: "No open payment reconciliation items", done: true, detail: "none open" },
      ],
      events: [],
      version: 0,
    },
    { month: "2026-10", state: "open", over: true, checklist: DONE, events: [], version: 0 },
    {
      month: "2026-09",
      state: "closed",
      over: true,
      checklist: DONE,
      events: [
        { action: "closed", reason: "the CA has September", actor: "admin Asha", at: ago(30 * 24 * 60) },
      ],
      version: 1,
    },
  ],
};

export const moneyReport = {
  interval: "month",
  from: "2026-10-01",
  to: "2026-10-31",
  figures: [
    {
      name: "gmv",
      terms: [
        { account: "host_payable", refType: "session", side: "credits", sign: 1 },
        { account: "platform_revenue", refType: "session", side: "credits", sign: 1 },
      ],
    },
    { name: "take", terms: [{ account: "platform_revenue", refType: null, side: "net", sign: 1 }] },
    {
      name: "refunds",
      terms: [{ account: "refunds_pending", refType: "credit_refund_processed", side: "debits", sign: 1 }],
    },
    {
      name: "chargebacks",
      terms: [{ account: "gateway_clearing", refType: "chargeback_settle", side: "credits", sign: 1 }],
    },
    { name: "promotions", terms: [{ account: "promotions", refType: null, side: "net", sign: 1 }] },
    { name: "gatewayFees", terms: [{ account: "gateway_fees", refType: null, side: "net", sign: 1 }] },
    {
      name: "net",
      terms: [
        { account: "platform_revenue", refType: null, side: "net", sign: 1 },
        { account: "promotions", refType: null, side: "net", sign: -1 },
        { account: "gateway_fees", refType: null, side: "net", sign: -1 },
      ],
    },
  ],
  rows: [
    {
      start: "2026-10-01",
      figures: {
        gmv: 1_250_000,
        take: 250_000,
        refunds: 30_000,
        chargebacks: 0,
        promotions: 50_000,
        gatewayFees: 23_600,
        net: 176_400,
      },
    },
  ],
};

export const liabilities = {
  at: ago(0),
  renterPurchasedPaise: 4_200_000,
  renterFreePaise: 350_000,
  hostPayablePaise: 900_000,
  hostPayableAged: {
    upTo7DaysPaise: 600_000,
    days8To14Paise: 250_000,
    days15To30Paise: 50_000,
    over30DaysPaise: 0,
  },
  breakage: [
    { month: "2026-10", expiredPaise: 12_000, toExpirePaise: 0 },
    { month: "2026-11", expiredPaise: 0, toExpirePaise: 40_000 },
  ],
};

// A payout batch previewed, not yet released: one host paid as previewed, one left out (ADR 0055).
export const batchItems = {
  items: [
    {
      userId: RENTER_ID,
      grossPaise: 16_000,
      recoveredPaise: 0,
      heldPaise: 0,
      tdsPaise: 160,
      feePaise: 0,
      netPaise: 15_840,
      upiMasked: "a•••@okicici",
      leftOut: null,
      leftOutWords: null,
      payoutId: null,
      payoutStatus: null,
    },
    {
      userId: OWNER_ID,
      grossPaise: 0,
      recoveredPaise: 0,
      heldPaise: 4_000,
      tdsPaise: 0,
      feePaise: 0,
      netPaise: 0,
      upiMasked: "r•••@ybl",
      leftOut: "on_hold",
      leftOutWords: "their UPI ID changed too recently",
      payoutId: null,
      payoutStatus: null,
    },
  ],
  nextCursor: null,
};

// Razorpay's settlements (ADR 0055): one matched, one with a fee above our rate still open.
export const SETTLEMENT_ITEM_ID = "01a1340a-0000-7000-8000-0000000c0001";
export const settlements = {
  settlements: [
    {
      id: "setl_Pq2",
      amountPaise: 38_584,
      feesPaise: 1_200,
      taxPaise: 216,
      status: "processed",
      utr: "UTR90210",
      settledAt: ago(24 * 60),
      state: "unmatched",
      txnId: null,
    },
    {
      id: "setl_Pq1",
      amountPaise: 78_112,
      feesPaise: 1_600,
      taxPaise: 288,
      status: "processed",
      utr: "UTR90209",
      settledAt: ago(2 * 24 * 60),
      state: "matched",
      txnId: "01a1340a-0000-7000-8000-0000000c0002",
    },
  ],
  nextCursor: null,
};

export const settlementItems = {
  items: [
    {
      id: SETTLEMENT_ITEM_ID,
      settlementId: "setl_Pq2",
      entityId: "pay_Pq2a",
      type: "payment",
      creditPaise: 40_000,
      debitPaise: 0,
      feePaise: 1_200,
      taxPaise: 216,
      ourPaise: 40_000,
      match: "unexpected_fee",
      state: "open",
      resolution: null,
      adjustmentId: null,
      reason: null,
      resolvedBy: null,
      resolvedAt: null,
      foundAt: ago(23 * 60),
      version: 1,
    },
  ],
  nextCursor: null,
};

// The CA's files and the packs made (ADR 0055).
export const taxExports = {
  exports: [
    {
      name: "invoice-register",
      description: "Every tax invoice and receipt issued in the month",
      contentType: "text/csv",
      inPack: true,
    },
    {
      name: "gstr1",
      description: "GSTR-1's data as JSON for the GST portal's offline tool (CA(confirm) on the format)",
      contentType: "application/json",
      inPack: true,
    },
    {
      name: "tds-26q",
      description: "26Q's deductee records for the quarter (CA(confirm) on the format)",
      contentType: "text/csv",
      inPack: false,
    },
    {
      name: "journal",
      description: "Every ledger entry dated in the month, by transaction",
      contentType: "text/csv",
      inPack: true,
    },
  ],
  packs: [
    {
      month: "2026-09",
      files: 9,
      manifestSha256: "3f2a9c0e5b7d4a1c8e6f0b2d4c6a8e0f1b3d5c7e9a0b2c4d6e8f0a1b3c5d7e9f",
      actor: "admin Asha",
      at: ago(30 * 24 * 60),
    },
  ],
};

/** The kill switches and maintenance mode (ADR 0056): top-ups paused for two hours, the rest running. */
export const flags = {
  items: [
    {
      id: "bookings.new",
      owner: "Operations",
      description: "Stops new bookings. Sessions already booked still start, and running sessions carry on.",
      pausedIfUnknown: false,
      emailsOwners: true,
      paused: false,
      until: null,
      resumeAutomatically: false,
      reason: null,
      changedBy: null,
      changedAt: null,
      version: 1,
    },
    {
      id: "topups",
      owner: "Finance",
      description:
        "Stops starting new top-ups. A payment already made is still credited, from its confirmation or Razorpay's webhook.",
      pausedIfUnknown: false,
      emailsOwners: true,
      paused: true,
      until: "2026-11-08T12:00:00Z",
      resumeAutomatically: true,
      reason: "A wave of failed UPI payments",
      changedBy: "01980000-0000-7000-8000-00000000a001",
      changedAt: "2026-11-08T10:00:00Z",
      version: 3,
    },
    {
      id: "payouts.send",
      owner: "Finance",
      description: "Stops sending payouts to RazorpayX.",
      pausedIfUnknown: true,
      emailsOwners: true,
      paused: false,
      until: null,
      resumeAutomatically: false,
      reason: null,
      changedBy: null,
      changedAt: null,
      version: 1,
    },
    {
      id: "maintenance",
      owner: "Operations",
      description: "Makes the app read-only, with a banner and the expected end.",
      pausedIfUnknown: false,
      emailsOwners: true,
      paused: false,
      until: null,
      resumeAutomatically: false,
      reason: null,
      changedBy: null,
      changedAt: null,
      version: 1,
    },
  ],
};

/** The staged rollouts (ADR 0056): the current release at 25% and doing as well as the rest; an older one paused by itself. */
export const rollouts = {
  items: [
    {
      id: "0.4.0",
      percent: 25,
      signedPercent: 100,
      paused: false,
      reason: "the first hosts have run a day without trouble",
      changedBy: "01980000-0000-7000-8000-00000000a001",
      changedAt: "2026-11-08T08:00:00Z",
      hosts: 3,
      sessions: 41,
      failureRate: 0.02,
      reconnectsPerHostDay: 0.4,
      restHosts: 9,
      restFailureRate: 0.03,
      restReconnectsPerHostDay: 0.5,
      version: 3,
    },
    {
      id: "0.3.9",
      percent: 5,
      signedPercent: null,
      paused: true,
      reason: "paused by itself: its hosts reconnected 6.0 times a day each against 0.5 on the rest",
      changedBy: null,
      changedAt: "2026-11-01T12:00:00Z",
      hosts: 1,
      sessions: 2,
      failureRate: 0.5,
      reconnectsPerHostDay: 6,
      restHosts: 11,
      restFailureRate: 0.03,
      restReconnectsPerHostDay: 0.5,
      version: 2,
    },
  ],
};

/** The fleet at a glance and its hosts (ADR 0056). */
export const fleetSummary = {
  hosts: 12,
  latestRelease: "0.4.0",
  stragglers: 9,
  offlineDay: 2,
  selfTestPassRate: 0.92,
  agentVersions: [
    { value: "0.3.9", hosts: 8 },
    { value: "0.4.0", hosts: 3 },
    { value: "0.3.2", hosts: 1 },
  ],
  distroVersions: [{ value: "0.1.4", hosts: 12 }],
  gpuModels: [
    { value: "NVIDIA GeForce RTX 4080 Laptop GPU", hosts: 7 },
    { value: "NVIDIA GeForce RTX 3060", hosts: 5 },
  ],
  driverVersions: [{ value: "566.14", hosts: 12 }],
  blocklistVersions: [
    { value: "2026-11-01T00:00:00Z", hosts: 11 },
    { value: "none", hosts: 1 },
  ],
};

export const fleet = {
  hosts: [
    {
      id: HOST_ID,
      ownerId: "01980000-0000-7000-8000-00000000c001",
      name: "ASUS ROG Strix",
      status: "active",
      agentVersion: "0.3.9",
      distroVersion: "0.1.4",
      gpuModel: "NVIDIA GeForce RTX 4080 Laptop GPU",
      driverVersion: "566.14",
      selfTestPassRate: 1,
      blocklistVersion: "2026-11-01T00:00:00Z",
      lastSeenAt: "2026-11-08T09:59:00Z",
      reconnectsPerDay: 0.29,
      straggler: true,
      version: 4,
    },
  ],
  nextCursor: null,
};

export const broadcasts = {
  items: [
    {
      id: "01a1c000-0000-7000-8000-00000000d001",
      subject: "Please update the IdleO app",
      body: "A newer version is ready.",
      audience: "agent_versions",
      tag: null,
      minAgentVersion: "0.3.0",
      maxAgentVersion: "0.3.9",
      email: true,
      sendAt: "2026-11-08T12:00:00Z",
      state: "scheduled",
      reached: null,
      createdBy: "01980000-0000-7000-8000-00000000a001",
      reason: "the old agents miss the blocklist fix",
      createdAt: "2026-11-08T09:00:00Z",
      sentAt: null,
      version: 1,
    },
  ],
  nextCursor: null,
};
