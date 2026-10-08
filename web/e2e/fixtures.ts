import type { components } from "../src/api/schema.gen";

type Schemas = components["schemas"];
export type Session = Schemas["SessionResponse"];
export type Wallet = Schemas["WalletResponse"];
export type Template = Schemas["TemplateResponse"];
type HistoryEntry = Schemas["HistoryEntry"];
type LogChunk = Schemas["LogChunk"];

/**
 * The one builder of invented data for every screen: the screens tests, the review gallery and the unit tests
 * (src/test/fixtures.ts). Nothing here is typed as a figure on its own: a session's charge comes from its minutes
 * and its price, the wallet from the grant less every charge, and the hold from what's running, so every screen
 * agrees with every other. src/test/fixtures.test.ts checks the rules on every account below:
 * - total = available + on hold;
 * - a running session's hold covers what it has used and not yet settled;
 * - the starting credit is a round grant, and the history adds up to the total.
 * The same every run, so screenshots compare.
 */
export const NOW = new Date("2026-10-07T06:34:00Z");

/** The starting credit: a round grant, as the operator gives it (docs/runbooks/granting-credits.md). */
export const GRANT = 50_000;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export const me = (createdAt = "2026-09-01T10:00:00Z") => ({
  id: "01a11300-0000-7000-8000-00000000000a",
  email: "asha@example.com",
  displayName: "Asha",
  status: "active",
  createdAt,
});

const IMAGE = "registry.example/idleo@sha256:0";

export const templateList: Template[] = [
  {
    id: "qlora-finetune",
    name: "QLoRA fine-tune of a small language model (Qwen 2.5 1.5B, 4-bit)",
    description: "Fine-tunes a 1.5B model on your dataset with QLoRA and saves the adapter as the output.",
    image: IMAGE,
    minVramMb: 8_192,
    defaultMaxRuntimeSeconds: 3_600,
    maxRuntimeCapSeconds: 14_400,
    typicalRuntimeSeconds: 2_700,
    params: [
      {
        name: "base_model",
        label: "Base model",
        help: "The model the adapter is trained on top of.",
        type: "choice",
        required: true,
        defaultValue: "qwen2.5-1.5b",
        choices: [
          "qwen2.5-0.5b",
          "qwen2.5-1.5b",
          "llama-3.2-1b",
          "llama-3.2-3b",
          "gemma-2-2b",
          "phi-3.5-mini",
        ],
      },
      {
        name: "epochs",
        label: "Epochs",
        help: "Passes over the whole dataset.",
        type: "integer",
        required: true,
        defaultValue: 3,
        min: 1,
        max: 10,
        choices: [],
      },
      {
        name: "learning_rate",
        label: "Learning rate",
        help: null,
        type: "number",
        required: false,
        defaultValue: 0.0002,
        min: 0.00001,
        max: 0.01,
        choices: [],
      },
      {
        name: "lora_rank",
        label: "LoRA rank",
        help: "Higher learns more and makes a bigger adapter.",
        type: "choice",
        required: true,
        defaultValue: "16",
        choices: ["8", "16", "32", "64"],
      },
      {
        name: "pack_sequences",
        label: "Pack short examples together",
        help: "Faster on datasets of short examples.",
        type: "boolean",
        required: false,
        defaultValue: true,
        choices: [],
      },
      {
        name: "prompt_template",
        label: "Prompt template",
        help: "How each example is laid out; {instruction} and {response} are filled in.",
        type: "string",
        required: false,
        defaultValue: null,
        maxLength: 2_000,
        choices: [],
      },
    ],
    input: {
      accepts: "required",
      upload: true,
      url: true,
      maxSizeMb: 20_480,
      label: "Dataset",
      help: "A JSONL file of instruction and response pairs.",
    },
  },
  {
    id: "echo-input",
    name: "Echo an input",
    description: "Reads its input and parameters back, proving the job saw them exactly.",
    image: IMAGE,
    minVramMb: 1_024,
    defaultMaxRuntimeSeconds: 300,
    maxRuntimeCapSeconds: 900,
    typicalRuntimeSeconds: 20,
    params: [
      {
        name: "greeting",
        label: "Greeting",
        help: "Written back in the output, once per repeat.",
        type: "string",
        required: true,
        defaultValue: null,
        maxLength: 200,
        choices: [],
      },
      {
        name: "repeat",
        label: "Repeat",
        help: "How many times the greeting is written back.",
        type: "integer",
        required: false,
        defaultValue: 1,
        min: 1,
        max: 5,
        choices: [],
      },
    ],
    input: {
      accepts: "required",
      upload: true,
      url: true,
      maxSizeMb: 512,
      label: "File to echo",
      help: "Any file; the output gives its size and SHA-256.",
    },
  },
  {
    id: "cuda-smoke-test",
    name: "CUDA smoke test",
    description: "Checks the GPU answers and CUDA works, in about 20 seconds.",
    image: IMAGE,
    minVramMb: 1_024,
    defaultMaxRuntimeSeconds: 300,
    maxRuntimeCapSeconds: 900,
    typicalRuntimeSeconds: null,
    params: [],
    input: { accepts: "none", upload: false, url: false, maxSizeMb: null, label: null, help: null },
  },
];

export const templates = { templates: templateList };

const GPU_4080 = { model: "NVIDIA GeForce RTX 4080 Laptop GPU", vramMb: 12_282 };
const GPU_3060 = { model: "NVIDIA GeForce RTX 3060 Laptop GPU", vramMb: 6_144 };
const GPU_4090 = { model: "NVIDIA GeForce RTX 4090", vramMb: 24_564 };

/** What's free now: prices are the hosts' own, in paise a minute (₹0.60 an hour is 1 paisa a minute). */
export const availability = {
  gpus: [
    {
      gpuModel: GPU_4080.model,
      vramMb: GPU_4080.vramMb,
      hostsOnline: 3,
      hostsIdle: 2,
      pricePaisePerMin: { min: 12, median: 14, max: 18 },
    },
    {
      gpuModel: GPU_3060.model,
      vramMb: GPU_3060.vramMb,
      hostsOnline: 2,
      hostsIdle: 1,
      pricePaisePerMin: { min: 6, median: 7, max: 8 },
    },
    {
      gpuModel: GPU_4090.model,
      vramMb: GPU_4090.vramMb,
      hostsOnline: 1,
      hostsIdle: 0,
      pricePaisePerMin: { min: 30, median: 30, max: 30 },
    },
  ],
};
export const noAvailability = { gpus: [] };

type State =
  | "REQUESTED"
  | "MATCHED"
  | "PROVISIONING"
  | "RUNNING"
  | "HOST_LOST"
  | "FINISHING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"
  | "NO_HOST_FREE";

interface Spec {
  /** Distinguishes the id; also the order sessions were booked in. */
  n: number;
  template: "qlora-finetune" | "echo-input" | "cuda-smoke-test";
  state: State;
  /** When it was booked, before NOW. */
  bookedAgoMs: number;
  /** How long it ran, for one that has ended; a live one has run until NOW. */
  ranMs?: number;
  price?: number;
  maxPrice?: number;
  maxSpend?: number;
  /** A session that lost its host: the second attempt, and the paise refunded for the lost minutes. */
  moved?: { refundPaise: number };
  errorCode?: string;
  /** Why it ended, for one that ended without completing. */
  endReason?: string;
  lowCredit?: boolean;
  gpu?: { model: string; vramMb: number };
  params?: Record<string, string | number | boolean>;
  input?: { uploadId?: string; url?: string };
}

const START_DELAY_MS = 40_000;
/** Metering settles every 15 minutes from minute 0 (ADR 0017). */
const WINDOW_MINUTES = 15;
const LIVE: ReadonlySet<State> = new Set([
  "REQUESTED",
  "MATCHED",
  "PROVISIONING",
  "RUNNING",
  "HOST_LOST",
  "FINISHING",
]);
const id = (n: number) => `01a11300-0000-7000-8000-${String(n).padStart(12, "0")}`;

interface Built {
  session: Session;
  refundPaise: number;
  settlements: { at: string; paise: number }[];
}

/** A session whose figures follow from its times and price, as metering would have charged it. */
function build(spec: Spec): Built {
  const price = spec.price ?? 2;
  const maxPrice = spec.maxPrice ?? Math.max(price, 5);
  const maxSpend = spec.maxSpend ?? 50_000;
  const template = templateList.find((t) => t.id === spec.template)!;
  const booked = NOW.getTime() - spec.bookedAgoMs;
  const live = LIVE.has(spec.state);
  const ran =
    spec.state === "REQUESTED" ||
    spec.state === "MATCHED" ||
    spec.state === "PROVISIONING" ||
    spec.state === "CANCELLED" ||
    spec.state === "NO_HOST_FREE" ||
    (spec.state === "FAILED" && spec.ranMs === 0)
      ? null
      : live
        ? NOW.getTime() - booked - START_DELAY_MS
        : (spec.ranMs ?? 150_000);
  const started = ran === null ? null : booked + START_DELAY_MS;
  // One that never ran ended when it was cancelled, found no host, or didn't start in time.
  const neverRan = spec.state === "CANCELLED" ? 30_000 : spec.state === "NO_HOST_FREE" ? 20_000 : 5 * MINUTE;
  const ended = live ? null : started === null ? booked + neverRan : started + ran!;
  const startedMinutes = ran === null ? 0 : Math.ceil(ran / MINUTE);

  // Every started minute is charged as a whole one; a live session's minutes are settled every 15.
  const used = Math.min(startedMinutes * price, maxSpend);
  const settledMinutes = live ? Math.floor(startedMinutes / WINDOW_MINUTES) * WINDOW_MINUTES : startedMinutes;
  const settlements: { at: string; paise: number }[] = [];
  for (let window = 0; window * WINDOW_MINUTES < settledMinutes; window++) {
    const minutes = Math.min(WINDOW_MINUTES, settledMinutes - window * WINDOW_MINUTES);
    const end =
      live || (window + 1) * WINDOW_MINUTES < settledMinutes
        ? started! + (window + 1) * WINDOW_MINUTES * MINUTE
        : ended! + 20_000;
    settlements.push({ at: new Date(end).toISOString(), paise: Math.min(minutes * price, maxSpend) });
  }
  const refundPaise = spec.moved?.refundPaise ?? 0;
  const settledPaise = settlements.reduce((sum, s) => sum + s.paise, 0);
  const charged = settledPaise - refundPaise;
  const accrued = live ? used - settledPaise : 0;
  // The hold: 30 minutes at the maximum price, never past the spending cap, renewed so it covers what's used.
  const reserved = live ? Math.max(Math.min(30 * maxPrice, maxSpend - settledPaise), accrued) : 0;

  const events: Session["events"] = [];
  const event = (state: string, reason: string, ms: number) =>
    events.push({ state, reason, at: new Date(ms).toISOString() });
  event("REQUESTED", "requested", booked);
  if (spec.state === "NO_HOST_FREE") {
    event("NO_HOST_FREE", spec.endReason ?? "no_candidate", ended!);
  } else {
    event("MATCHED", "matched", booked + 2_000);
    if (spec.state !== "MATCHED") {
      event("PROVISIONING", "offer_accepted", booked + 3_000);
    }
  }
  if (started !== null) {
    event("RUNNING", "running", started);
    if (spec.moved) {
      const lost = started + Math.floor((ran ?? 0) / 2);
      event("HOST_LOST", "lease_lapsed", lost);
      event("REQUESTED", "rematch", lost + 1_000);
      event("MATCHED", "matched", lost + 3_000);
      event("PROVISIONING", "offer_accepted", lost + 4_000);
      event("RUNNING", "running", lost + 30_000);
    }
    if (spec.state === "FINISHING") {
      event("FINISHING", spec.endReason ?? "renter_stopped", NOW.getTime() - 5_000);
    } else if (spec.state === "COMPLETED") {
      const stopped = spec.endReason === "renter_stopped";
      event("FINISHING", stopped ? "renter_stopped" : "job_succeeded", ended! - 1_000);
      event("COMPLETED", stopped ? "stopped" : "outputs_complete", ended!);
    } else if (spec.state === "FAILED") {
      if (spec.endReason === "credit_exhausted" || spec.endReason === "max_spend_reached") {
        event("FINISHING", spec.endReason, ended! - 2_000);
      }
      event("FAILED", spec.errorCode ?? "job_failed", ended!);
    }
  } else if (spec.state === "CANCELLED") {
    event("CANCELLED", "renter_cancelled", ended!);
  } else if (spec.state === "FAILED") {
    event("FAILED", spec.endReason ?? "start_timeout", ended!);
  }

  const checkpoints: Session["checkpoints"] = [];
  if (started !== null && ran !== null && ran > 3 * MINUTE) {
    const every = 5 * MINUTE;
    for (let seq = 1; seq * every < ran; seq++) {
      checkpoints.push({
        attempt: spec.moved && seq * every > ran / 2 ? 2 : 1,
        seq,
        completedAt: new Date(started + seq * every).toISOString(),
      });
    }
  }

  return {
    session: {
      id: id(spec.n),
      state: spec.state,
      attempt: spec.moved ? 2 : 1,
      errorCode: spec.state === "FAILED" ? (spec.errorCode ?? spec.endReason ?? "job_failed") : null,
      templateId: template.id,
      hostId: started !== null && live ? "01a11300-0000-7000-8000-0000000000aa" : null,
      pricePaisePerMin: spec.state === "REQUESTED" || spec.state === "NO_HOST_FREE" ? null : price,
      maxPricePaisePerMin: maxPrice,
      maxSpendPaise: maxSpend,
      vramNeededMb: template.minVramMb,
      maxRuntimeSeconds: template.defaultMaxRuntimeSeconds,
      reservedPaise: reserved,
      chargedPaise: charged,
      accruedPaise: accrued,
      lowCredit: spec.lowCredit ?? false,
      settled: !live,
      createdAt: new Date(booked).toISOString(),
      startedAt: started === null ? null : new Date(started).toISOString(),
      endedAt: ended === null ? null : new Date(ended).toISOString(),
      gpu:
        started === null && spec.state !== "MATCHED" && spec.state !== "PROVISIONING"
          ? null
          : (spec.gpu ?? GPU_4080),
      params: spec.params ?? defaults(template),
      input: spec.input ? { uploadId: spec.input.uploadId ?? null, url: spec.input.url ?? null } : null,
      checkpoints,
      events,
    },
    refundPaise,
    settlements,
  };
}

function defaults(template: Template): Session["params"] {
  return Object.fromEntries(
    template.params.flatMap((param) =>
      param.defaultValue === null || param.defaultValue === undefined
        ? []
        : [[param.name, param.defaultValue]],
    ),
  );
}

/** An account: its sessions, newest first, and the wallet and history they leave behind. */
export interface Account {
  wallet: Wallet;
  sessions: Session[];
  history: HistoryEntry[];
  grant: number;
}

export function account(specs: Spec[], { grant = GRANT, createdAt = "2026-09-01T10:00:00Z" } = {}): Account {
  const built = specs.map(build).sort((a, b) => b.session.createdAt.localeCompare(a.session.createdAt));
  const entries: HistoryEntry[] =
    grant === 0
      ? []
      : [
          {
            id: id(9_000),
            amountPaise: grant,
            direction: "credit",
            kind: "free_credit",
            reference: { type: "grant", id: id(9_001) },
            createdAt: createdAt,
          },
        ];
  let n = 9_100;
  for (const { session, settlements, refundPaise } of built) {
    for (const settlement of settlements) {
      entries.push({
        id: id(n++),
        amountPaise: settlement.paise,
        direction: "debit",
        kind: "session_charge",
        reference: { type: "session", id: session.id },
        createdAt: settlement.at,
      });
    }
    if (refundPaise > 0) {
      entries.push({
        id: id(n++),
        amountPaise: refundPaise,
        direction: "credit",
        kind: "session_refund",
        reference: { type: "session_refund", id: session.id },
        createdAt: new Date(new Date(session.endedAt ?? NOW).getTime() + 30_000).toISOString(),
      });
    }
  }
  entries.sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
  const balancePaise = entries.reduce(
    (sum, entry) => sum + (entry.direction === "credit" ? entry.amountPaise : -entry.amountPaise),
    0,
  );
  const reservedPaise = built.reduce((sum, b) => sum + b.session.reservedPaise, 0);
  const sessions = built.map((b) => b.session);
  return {
    wallet: { balancePaise, reservedPaise, availablePaise: balancePaise - reservedPaise },
    sessions,
    history: entries,
    grant,
  };
}

const UPLOAD_ID = "01a11300-0000-7000-8000-00000000f11e";

/** Asha's account: a QLoRA fine-tune running now, and three weeks of sessions behind it. */
export const asha = account([
  {
    n: 1,
    template: "qlora-finetune",
    state: "RUNNING",
    bookedAgoMs: 22 * MINUTE + 40_000,
    price: 14,
    maxPrice: 18,
    gpu: GPU_4080,
    input: { uploadId: UPLOAD_ID },
  },
  {
    n: 2,
    template: "echo-input",
    state: "COMPLETED",
    bookedAgoMs: 2 * HOUR,
    ranMs: 22_000,
    price: 12,
    params: { greeting: "hello", repeat: 3 },
    input: { uploadId: UPLOAD_ID },
  },
  {
    n: 3,
    template: "qlora-finetune",
    state: "FAILED",
    bookedAgoMs: 5 * HOUR,
    ranMs: 6 * MINUTE + 10_000,
    price: 14,
    errorCode: "oom_killed",
    params: { base_model: "llama-3.2-3b", epochs: 3, lora_rank: "64" },
  },
  {
    n: 4,
    template: "qlora-finetune",
    state: "COMPLETED",
    bookedAgoMs: DAY + 3 * HOUR,
    ranMs: 41 * MINUTE,
    price: 12,
    moved: { refundPaise: 36 },
  },
  {
    n: 5,
    template: "cuda-smoke-test",
    state: "COMPLETED",
    bookedAgoMs: DAY + 4 * HOUR,
    ranMs: 20_000,
    price: 6,
    gpu: GPU_3060,
  },
  {
    n: 6,
    template: "qlora-finetune",
    state: "COMPLETED",
    bookedAgoMs: 2 * DAY,
    ranMs: 18 * MINUTE,
    price: 14,
    endReason: "renter_stopped",
  },
  { n: 7, template: "echo-input", state: "CANCELLED", bookedAgoMs: 2 * DAY + 2 * HOUR },
  {
    n: 8,
    template: "qlora-finetune",
    state: "NO_HOST_FREE",
    bookedAgoMs: 3 * DAY,
    endReason: "offers_exhausted",
  },
  ...Array.from({ length: 14 }, (_, i): Spec => ({
    n: 20 + i,
    template: i % 3 === 0 ? "qlora-finetune" : i % 3 === 1 ? "echo-input" : "cuda-smoke-test",
    state: i === 5 ? "FAILED" : "COMPLETED",
    errorCode: i === 5 ? "job_failed" : undefined,
    bookedAgoMs: (4 + i) * DAY + i * HOUR,
    ranMs: i % 3 === 0 ? (30 + i) * MINUTE : 20_000 + i * 1_000,
    price: i % 3 === 0 ? 12 : 6,
    gpu: i % 3 === 0 ? GPU_4080 : GPU_3060,
  })),
]);

/** A new account: the grant and nothing else. */
export const fresh = account([], { createdAt: NOW.toISOString() });

/** An account that hasn't been given any credit yet. */
export const none = account([], { grant: 0, createdAt: NOW.toISOString() });

/** Credit nearly spent, with a session running on what's left. */
export const low = account([
  {
    n: 101,
    template: "qlora-finetune",
    state: "RUNNING",
    bookedAgoMs: 9 * MINUTE + 40_000,
    price: 14,
    maxPrice: 18,
    lowCredit: true,
  },
  {
    n: 102,
    template: "qlora-finetune",
    state: "COMPLETED",
    bookedAgoMs: 3 * DAY,
    ranMs: 490 * MINUTE,
    price: 100,
    maxPrice: 100,
  },
]);

/** Every account, for the rules test. */
export const accounts = { asha, fresh, none, low } as const;

/** One session from Asha's account, by state. */
const find = (n: number) => asha.sessions.find((session) => session.id === id(n))!;
export const running = find(1);
export const completed = find(2);
export const failed = find(3);
export const moved = find(4);
export const stopped = find(6);
export const cancelled = find(7);
export const noHostFree = find(8);
export const lowCreditRunning = low.sessions.find((session) => session.state === "RUNNING")!;

/** Sessions on their own, for screens that show one: the figures follow the same rules. */
export const finding = build({
  n: 201,
  template: "echo-input",
  state: "REQUESTED",
  bookedAgoMs: 8_000,
  params: { greeting: "hello", repeat: 3 },
  input: { uploadId: UPLOAD_ID },
}).session;
export const movedLive = build({
  n: 202,
  template: "qlora-finetune",
  state: "RUNNING",
  bookedAgoMs: 34 * MINUTE,
  price: 14,
  maxPrice: 18,
  moved: { refundPaise: 0 },
}).session;

/** The wallet a single extra live session leaves Asha's account with. */
export const walletWith = (...extra: Session[]): Wallet => {
  const reserved = asha.wallet.reservedPaise + extra.reduce((sum, s) => sum + s.reservedPaise, 0);
  return {
    balancePaise: asha.wallet.balancePaise,
    reservedPaise: reserved,
    availablePaise: asha.wallet.balancePaise - reserved,
  };
};

/** A page of the session list, as GET /v1/sessions serves it. */
export function sessionsPage(
  source: Session[],
  { active, cursor, limit = 20 }: { active?: boolean; cursor?: string | null; limit?: number } = {},
) {
  const list = source.filter((session) => active === undefined || active === !session.settled);
  const start = cursor ? Number(cursor) : 0;
  const page = list.slice(start, start + limit);
  return { sessions: page, nextCursor: start + limit < list.length ? String(start + limit) : null };
}

/** A page of credit history, as GET /v1/wallet/transactions serves it. */
export function history(page: number, source: Account = asha) {
  const entries = source.history.slice(page * 20, page * 20 + 20);
  return { entries, nextCursor: (page + 1) * 20 < source.history.length ? `page-${page + 2}` : null };
}

export const outputs = {
  sessionId: completed.id,
  attempt: 1,
  linksExpireAt: new Date(NOW.getTime() + 15 * MINUTE).toISOString(),
  files: [
    { path: "result.json", sizeBytes: 412, url: "http://localhost:3000/assets/fixture-output" },
    {
      path: "checkpoints/final/adapter.safetensors",
      sizeBytes: 18_874_368,
      url: "http://localhost:3000/assets/fixture",
    },
  ],
};

/** The running fine-tune's log, as the agent's sink uploaded it: a chunk every few seconds. */
export function logChunks(session: Session, count = 6): LogChunk[] {
  const lines = (seq: number) =>
    seq === 1
      ? [
          `starting session ${session.id} attempt 1`,
          "parameter SESSION_PARAM_BASE_MODEL=qwen2.5-1.5b",
          "parameter SESSION_PARAM_EPOCHS=3",
          "input dataset.jsonl 48213504 bytes",
          "loading the model in 4-bit",
        ]
      : Array.from(
          { length: 4 },
          (_, i) =>
            `epoch 1 step ${(seq - 2) * 40 + (i + 1) * 10}/1200 loss ${(2.4 - seq * 0.11 - i * 0.02).toFixed(3)}`,
        );
  return Array.from({ length: count }, (_, i) => ({
    attempt: 1,
    seq: i + 1,
    text: `${lines(i + 1).join("\n")}\n`,
  }));
}

/** The quote for a booking (POST /v1/session-quotes): the hold is 30 minutes at the maximum price. */
export function quote(
  maxPricePaisePerMin: number,
  maxSpendPaise: number,
  wallet: Wallet = asha.wallet,
  fits = availability,
) {
  const holdPaise = Math.min(30 * maxPricePaisePerMin, maxSpendPaise);
  const fitting = fits.gpus.filter(
    (gpu) => gpu.hostsIdle > 0 && gpu.pricePaisePerMin.min <= maxPricePaisePerMin,
  );
  return {
    holdPaise,
    availablePaise: wallet.availablePaise,
    enoughCredit: wallet.availablePaise >= holdPaise,
    hostsFitNow: fitting.reduce((sum, gpu) => sum + gpu.hostsIdle, 0),
    pricePaisePerMin:
      fitting.length === 0
        ? null
        : {
            min: Math.min(...fitting.map((gpu) => gpu.pricePaisePerMin.min)),
            max: Math.max(...fitting.map((gpu) => gpu.pricePaisePerMin.max)),
          },
  };
}

/** An upload as POST /v1/uploads opens it: one link per part. */
export function newUpload(filename: string, sizeBytes: number, partSizeBytes = 5 * 1024 * 1024) {
  const partCount = Math.max(1, Math.ceil(sizeBytes / partSizeBytes));
  return {
    id: UPLOAD_ID,
    filename,
    sizeBytes,
    partSizeBytes,
    partCount,
    status: "pending" as const,
    createdAt: NOW.toISOString(),
    expiresAt: new Date(NOW.getTime() + DAY).toISOString(),
    urlsExpireAt: new Date(NOW.getTime() + HOUR).toISOString(),
    parts: Array.from({ length: partCount }, (_, i) => ({
      number: i + 1,
      url: `http://localhost:9100/idleo-local/uploads/${UPLOAD_ID}/part-${i + 1}`,
    })),
  };
}
