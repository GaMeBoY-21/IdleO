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

export type Service = Schemas["ServiceResponse"];

/** The services, as the production catalog groups them (templates/catalog.yaml); each has a template here. */
export const serviceList: Service[] = [
  {
    id: "fine-tune",
    name: "Fine-tune a model",
    tagline: "a model on your data",
    runningLabel: "Fine-tuning",
    icon: "layers",
    hidden: false,
  },
  {
    id: "run-code",
    name: "Run your code",
    tagline: "your folder, our GPU",
    runningLabel: "Running your code",
    icon: "code",
    hidden: false,
  },
  {
    id: "diagnostics",
    name: "Diagnostics",
    tagline: "check a GPU works",
    runningLabel: "Checking the GPU",
    icon: "pulse",
    hidden: true,
  },
];

const GIB = 1024 ** 3;

/** A basic parameter, asked as a plain question. */
function question(param: string, label: string, help: string | null = null): Template["questions"][number] {
  return {
    kind: "param",
    label,
    help,
    name: null,
    options: [],
    defaultOption: null,
    param,
    choicesGb: [],
    defaultGb: null,
  };
}

/** How long the fine-tune's presets take, and the epochs each sets. */
const THOROUGHNESS = [
  { id: "quick", label: "Quick", minutes: 15, sets: { epochs: 1 } },
  { id: "standard", label: "Standard", minutes: 45, sets: { epochs: 3 } },
  { id: "thorough", label: "Thorough", minutes: 75, sets: { epochs: 5 } },
];

const BUDGET = { budgetChips: [5_000, 10_000, 50_000], defaultBudgetPaise: 10_000 };

/**
 * The templates, as the development catalog's simulated ones are shaped (templates/development.yaml): the renter
 * brings the model, the data and the code; nothing here supplies a model (ADR 0022).
 */
export const templateList: Template[] = [
  {
    id: "qlora-finetune",
    service: "fine-tune",
    name: "Fine-tune your model (LoRA)",
    description: "Bring your model and your examples; you get back a LoRA adapter for your model.",
    image: IMAGE,
    minVramMb: 6_144,
    defaultMaxRuntimeSeconds: 7_200,
    maxRuntimeCapSeconds: 14_400,
    typicalRuntimeSeconds: 2_700,
    params: [
      {
        name: "epochs",
        label: "Epochs",
        help: "How many times training goes through your examples.",
        type: "integer",
        group: "advanced",
        required: false,
        defaultValue: 3,
        min: 1,
        max: 5,
        choices: [],
      },
      {
        name: "learning_rate",
        label: "Learning rate",
        help: "How big each training step is.",
        type: "number",
        group: "advanced",
        required: false,
        defaultValue: 0.0002,
        min: 0.000001,
        max: 0.01,
        choices: [],
      },
      {
        name: "lora_rank",
        label: "LoRA rank",
        help: "A higher rank learns more, and makes a bigger adapter.",
        type: "choice",
        group: "advanced",
        required: false,
        defaultValue: "16",
        choices: ["8", "16", "32"],
      },
      {
        name: "pack_sequences",
        label: "Pack short examples together",
        help: "Faster on datasets of short examples.",
        type: "boolean",
        group: "advanced",
        required: false,
        defaultValue: true,
        choices: [],
      },
      {
        name: "prompt_template",
        label: "Prompt format",
        help: "How each example is laid out; {instruction} and {response} are filled in.",
        type: "string",
        group: "advanced",
        required: false,
        defaultValue: null,
        maxLength: 2_000,
        choices: [],
      },
    ],
    inputs: [
      {
        name: "model",
        label: "Your model",
        help: "Upload the model folder from your laptop, pick it from My files, or paste a Hugging Face link.",
        kind: "folder",
        upload: true,
        link: true,
        required: true,
        maxSizeBytes: 40 * GIB,
      },
      {
        name: "data",
        label: "Your training data",
        help: "A JSONL file, one example a line.",
        kind: "file",
        upload: true,
        link: true,
        required: true,
        maxSizeBytes: 10 * GIB,
      },
    ],
    questions: [
      {
        kind: "preset",
        name: "thoroughness",
        label: "How thorough?",
        help: null,
        options: THOROUGHNESS,
        defaultOption: "standard",
        param: null,
        choicesGb: [],
        defaultGb: null,
      },
    ],
    ...BUDGET,
    vramFromInput: { input: "model", baseMb: 2_048, perGbMb: 700 },
    youGet: "adapter_model.safetensors and its training log",
  },
  {
    id: "run-python",
    service: "run-code",
    name: "Run your Python project",
    description:
      "Bring your project folder; we run your command on a GPU and you get back what it writes to outputs/.",
    image: IMAGE,
    minVramMb: 4_096,
    defaultMaxRuntimeSeconds: 3_600,
    maxRuntimeCapSeconds: 14_400,
    typicalRuntimeSeconds: null,
    params: [
      {
        name: "command",
        label: "Command",
        help: "What to run, from the top of your folder.",
        type: "string",
        group: "basic",
        required: false,
        defaultValue: "python main.py",
        maxLength: 500,
        choices: [],
      },
    ],
    inputs: [
      {
        name: "code",
        label: "Your code",
        help: "Your project folder, or a zip of it. Anything it writes to outputs/ comes back to you.",
        kind: "folder",
        upload: true,
        link: false,
        required: true,
        maxSizeBytes: 10 * GIB,
      },
      {
        name: "data",
        label: "Your data",
        help: "A folder your code reads, if it needs one.",
        kind: "folder",
        upload: true,
        link: false,
        required: false,
        maxSizeBytes: 40 * GIB,
      },
    ],
    questions: [
      question("command", "Command"),
      {
        kind: "gpu_memory",
        name: null,
        label: "GPU memory you need",
        help: null,
        options: [],
        defaultOption: null,
        param: null,
        choicesGb: [8, 12, 16, 24],
        defaultGb: 8,
      },
    ],
    ...BUDGET,
    youGet: "whatever your code writes to outputs/",
  },
  {
    id: "echo-input",
    service: "diagnostics",
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
        group: "basic",
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
        group: "advanced",
        required: false,
        defaultValue: 1,
        min: 1,
        max: 5,
        choices: [],
      },
    ],
    inputs: [
      {
        name: "file",
        label: "File to echo",
        help: "Any file; the output gives its size and SHA-256.",
        kind: "file",
        upload: true,
        link: true,
        required: true,
        maxSizeBytes: 512 * 1024 ** 2,
      },
    ],
    questions: [question("greeting", "Greeting", "Written back in the output, once per repeat.")],
    budgetChips: [],
    defaultBudgetPaise: null,
  },
  {
    id: "cuda-smoke-test",
    service: "diagnostics",
    name: "CUDA smoke test",
    description: "Checks the GPU answers and CUDA works, in about 20 seconds.",
    image: IMAGE,
    minVramMb: 1_024,
    defaultMaxRuntimeSeconds: 300,
    maxRuntimeCapSeconds: 900,
    typicalRuntimeSeconds: null,
    params: [],
    inputs: [],
    questions: [],
    budgetChips: [],
    defaultBudgetPaise: null,
  },
];

export const templates = { services: serviceList, templates: templateList };

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
  /** Its data: the template's "data" input, or its only one. */
  input?: { uploadId?: string; url?: string };
  /** A fine-tune's model. */
  model?: { uploadId?: string; url?: string };
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
          : {
              ...(spec.gpu ?? GPU_4080),
              // How busy and how hot, from its host's heartbeats: only while it runs.
              utilPct: spec.state === "RUNNING" ? 97 : null,
              tempC: spec.state === "RUNNING" ? 71 : null,
            },
      params: spec.params ?? defaults(template),
      presets: Object.fromEntries(
        template.questions.flatMap((q) => (q.name && q.defaultOption ? [[q.name, q.defaultOption]] : [])),
      ),
      inputs: {
        ...(spec.model
          ? { model: { uploadId: spec.model.uploadId ?? null, url: spec.model.url ?? null } }
          : {}),
        ...(spec.input
          ? { [dataInput(template)]: { uploadId: spec.input.uploadId ?? null, url: spec.input.url ?? null } }
          : {}),
      },
      checkpoints,
      events,
    },
    refundPaise,
    settlements,
  };
}

/** The input a session's data goes in: the template's "data" input, or its first. */
const dataInput = (template: Template) =>
  template.inputs.find((input) => input.name === "data")?.name ?? template.inputs[0]?.name ?? "data";

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
const MODEL_ID = "01a11300-0000-7000-8000-00000000f11f";

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
    model: { uploadId: MODEL_ID },
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
    params: { epochs: 3, lora_rank: "64" },
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
export const found = build({
  n: 203,
  template: "qlora-finetune",
  state: "PROVISIONING",
  bookedAgoMs: 26_000,
  price: 14,
  maxPrice: 18,
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
          "parameter SESSION_PARAM_EPOCHS=3",
          "input data/dataset.jsonl 48213504 bytes",
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

/**
 * The quote for a booking (POST /v1/session-quotes, ADR 0021): the hold is 30 minutes at the ceiling, never past
 * the spending cap. Without a ceiling of the renter's own, the server's is the highest price among the GPUs free
 * now with enough memory; null when none is (the API's no-gpu-free).
 */
export function quote(
  maxPricePaisePerMin: number | null | undefined,
  maxSpendPaise: number,
  wallet: Wallet = asha.wallet,
  fits = availability,
  vramNeededMb = 0,
) {
  const free = fits.gpus.filter((gpu) => gpu.hostsIdle > 0 && gpu.vramMb >= vramNeededMb);
  const ceiling = maxPricePaisePerMin ?? Math.max(0, ...free.map((gpu) => gpu.pricePaisePerMin.max));
  if (maxPricePaisePerMin == null && free.length === 0) {
    return null;
  }
  const holdPaise = Math.min(30 * ceiling, maxSpendPaise);
  const fitting = free.filter((gpu) => gpu.pricePaisePerMin.min <= ceiling);
  return {
    ceilingPaisePerMin: ceiling,
    vramNeededMb,
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

/** How the fake API answers a quote's body: the quote, or no-gpu-free (409) when nothing fits and no ceiling is given. */
export function quoteAnswer(
  body: { maxPricePaisePerMin?: number | null; maxSpendPaise: number; vramNeededMb?: number },
  wallet: Wallet = asha.wallet,
  fits = availability,
) {
  const answer = quote(body.maxPricePaisePerMin, body.maxSpendPaise, wallet, fits, body.vramNeededMb ?? 0);
  return answer === null ? { status: 409, problem: "no-gpu-free" } : { json: answer };
}

/** An upload of one file as POST /v1/uploads opens it, or (`ready`) as completing it leaves it. */
export function newUpload(
  filename: string,
  sizeBytes: number,
  status: "pending" | "ready" = "pending",
  partSizeBytes = 5 * 1024 * 1024,
) {
  const partCount = Math.ceil(sizeBytes / partSizeBytes);
  return {
    upload: {
      id: UPLOAD_ID,
      name: filename,
      sizeBytes,
      fileCount: 1,
      status,
      partSizeBytes,
      createdAt: NOW.toISOString(),
      completedAt: status === "ready" ? NOW.toISOString() : null,
      lastUsedAt: null,
      expiresAt: new Date(NOW.getTime() + (status === "ready" ? 30 * DAY : DAY)).toISOString(),
    },
    files: [{ path: filename, sizeBytes, partCount, done: status === "ready" }],
  };
}

/**
 * An upload of these files, as POST /v1/uploads opens it (the screen's own request body) or, `ready`, as completing it
 * leaves it: 5 MiB parts, as the local stack's storage has them.
 */
export function uploadOf(
  body: { name: string; files: { path: string; sizeBytes: number }[] },
  status: "pending" | "ready" = "pending",
  partSizeBytes = 5 * 1024 * 1024,
) {
  const one = newUpload(body.name, 0, status, partSizeBytes);
  const sizeBytes = body.files.reduce((sum, file) => sum + file.sizeBytes, 0);
  return {
    upload: { ...one.upload, sizeBytes, fileCount: body.files.length },
    files: body.files.map((file) => ({
      ...file,
      partCount: Math.ceil(file.sizeBytes / partSizeBytes),
      done: status === "ready",
    })),
  };
}

/** The running fine-tune's data, as GET /v1/uploads/{id} describes it. */
export const sessionUpload = newUpload("dataset.jsonl", 48_213_504, "ready");

type UploadInfo = Schemas["UploadResponse"];

/** A ready upload in My files, last used `usedAgoMs` ago (null: never), kept 30 days after that. */
function kept(
  id: string,
  name: string,
  sizeBytes: number,
  fileCount: number,
  uploadedAgoMs: number,
  usedAgoMs: number | null,
): UploadInfo {
  const uploaded = NOW.getTime() - uploadedAgoMs;
  const used = usedAgoMs === null ? null : NOW.getTime() - usedAgoMs;
  return {
    id,
    name,
    sizeBytes,
    fileCount,
    status: "ready",
    partSizeBytes: 64 * 1024 * 1024,
    createdAt: new Date(uploaded - MINUTE).toISOString(),
    completedAt: new Date(uploaded).toISOString(),
    lastUsedAt: used === null ? null : new Date(used).toISOString(),
    expiresAt: new Date((used ?? uploaded) + 30 * DAY).toISOString(),
  };
}

/**
 * Asha's My files, newest first: the model and data her running fine-tune uses (last used when it was booked), a
 * code project, and a 38 GiB model, more than any GPU here can hold once loaded (2,048 + 700 x 38 = 28,648 MB).
 */
export const myFiles: UploadInfo[] = [
  kept(id(9_303), "llama-3-70b", 38 * 1024 ** 3, 31, 2 * HOUR, null),
  kept(UPLOAD_ID, "support-chats.jsonl", 48_213_504, 1, DAY, 22 * MINUTE + 40_000),
  kept(MODEL_ID, "my-llm-7b", Math.round(4.6 * 1024 ** 3), 4, 3 * DAY, 22 * MINUTE + 40_000),
  kept(id(9_302), "vision-project.zip", 12_582_912, 1, 4 * DAY, 2 * DAY),
];
export const tooBigModel = myFiles[0]!;

/** My files as GET /v1/uploads serves it. */
export const uploadsPage = (uploads: UploadInfo[] = myFiles) => ({ uploads, nextCursor: null });

/** Part links as POST /v1/uploads/{id}/part-urls signs them, for the parts asked for. */
export function partLinks(partNumbers: number[]) {
  return {
    parts: partNumbers.map((number) => ({
      number,
      url: `http://localhost:9100/idleo-local/uploads/${UPLOAD_ID}/part-${number}`,
    })),
    urlsExpireAt: new Date(NOW.getTime() + HOUR).toISOString(),
  };
}
