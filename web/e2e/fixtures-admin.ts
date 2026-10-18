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
};

export const pendingHost = {
  ...host,
  id: PENDING_HOST_ID,
  name: "Meera's desktop",
  status: "pending",
  online: true,
  pricePaisePerMin: 10,
  createdAt: ago(40),
  statusHistory: [],
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
