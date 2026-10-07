import type { components } from "../src/api/schema.gen";

type Session = components["schemas"]["SessionResponse"];

/** Fixed, invented data for the screens tests: the same every run, so screenshots compare. */
export const NOW = new Date("2026-10-07T06:34:00Z");

export const me = (createdAt = "2026-09-01T10:00:00Z") => ({
  id: "01a11300-0000-7000-8000-00000000000a",
  email: "asha@example.com",
  displayName: "Asha",
  status: "active",
  createdAt,
});

export const wallet = { balancePaise: 50_000, reservedPaise: 60, availablePaise: 49_940 };
export const emptyWallet = { balancePaise: 0, reservedPaise: 0, availablePaise: 0 };

export const templates = {
  templates: [
    {
      id: "cuda-smoke-test",
      name: "CUDA smoke test",
      image: "nvidia/cuda@sha256:0",
      minVramMb: 1024,
      defaultMaxRuntimeSeconds: 300,
      maxRuntimeCapSeconds: 900,
    },
    {
      id: "qlora-finetune",
      name: "QLoRA fine-tune (Qwen 2.5 1.5B)",
      image: "idleo/qlora@sha256:0",
      minVramMb: 8192,
      defaultMaxRuntimeSeconds: 3600,
      maxRuntimeCapSeconds: 14400,
    },
  ],
};

const base: Session = {
  id: "01a11300-0000-7000-8000-000000000001",
  state: "RUNNING",
  attempt: 1,
  errorCode: null,
  templateId: "qlora-finetune",
  hostId: "01a11300-0000-7000-8000-0000000000aa",
  pricePaisePerMin: 80,
  maxPricePaisePerMin: 100,
  maxSpendPaise: 50_000,
  vramNeededMb: 8192,
  maxRuntimeSeconds: 3600,
  reservedPaise: 2_400,
  chargedPaise: 1_200,
  accruedPaise: 160,
  lowCredit: false,
  createdAt: "2026-10-07T06:10:00Z",
  startedAt: "2026-10-07T06:12:00Z",
  events: [
    { state: "REQUESTED", reason: "requested", at: "2026-10-07T06:10:00Z" },
    { state: "MATCHED", reason: "matched", at: "2026-10-07T06:10:02Z" },
    { state: "PROVISIONING", reason: "offer_accepted", at: "2026-10-07T06:10:03Z" },
    { state: "RUNNING", reason: "running", at: "2026-10-07T06:12:00Z" },
  ],
};

export const running = base;

export const completed: Session = {
  ...base,
  id: "01a11300-0000-7000-8000-000000000002",
  state: "COMPLETED",
  templateId: "cuda-smoke-test",
  pricePaisePerMin: 2,
  maxSpendPaise: 10_000,
  reservedPaise: 0,
  chargedPaise: 6,
  accruedPaise: 0,
  createdAt: "2026-10-06T14:00:00Z",
  startedAt: "2026-10-06T14:00:05Z",
  events: [
    { state: "REQUESTED", reason: "requested", at: "2026-10-06T14:00:00Z" },
    { state: "MATCHED", reason: "matched", at: "2026-10-06T14:00:01Z" },
    { state: "PROVISIONING", reason: "offer_accepted", at: "2026-10-06T14:00:02Z" },
    { state: "RUNNING", reason: "running", at: "2026-10-06T14:00:05Z" },
    { state: "FINISHING", reason: "succeeded", at: "2026-10-06T14:02:35Z" },
    { state: "COMPLETED", reason: "completed", at: "2026-10-06T14:02:36Z" },
  ],
};

export const failed: Session = {
  ...completed,
  id: "01a11300-0000-7000-8000-000000000003",
  state: "FAILED",
  errorCode: "oom_killed",
  chargedPaise: 2,
  createdAt: "2026-10-05T09:00:00Z",
  startedAt: "2026-10-05T09:00:04Z",
  events: [
    { state: "REQUESTED", reason: "requested", at: "2026-10-05T09:00:00Z" },
    { state: "RUNNING", reason: "running", at: "2026-10-05T09:00:04Z" },
    { state: "FAILED", reason: "oom_killed", at: "2026-10-05T09:00:40Z" },
  ],
};

export const outputs = {
  sessionId: completed.id,
  attempt: 1,
  linksExpireAt: "2026-10-07T06:49:00Z",
  files: [
    { path: "result.json", sizeBytes: 182, url: "http://localhost:3000/assets/fixture-output" },
    {
      path: "checkpoints/final/adapter.safetensors",
      sizeBytes: 18_874_368,
      url: "http://localhost:3000/assets/fixture",
    },
  ],
};

export function history(page: number) {
  const kinds = ["session_charge", "session_refund", "session_charge", "free_credit"];
  const entries = Array.from({ length: page === 0 ? 20 : 3 }, (_, index) => {
    const n = page * 20 + index;
    const kind = n === 22 ? "free_credit" : (kinds[n % 3] ?? "session_charge");
    return {
      id: `01a11300-0000-7000-8000-${String(1000 + n).padStart(12, "0")}`,
      amountPaise: kind === "free_credit" ? 50_000 : kind === "session_refund" ? 4 : 6 + n,
      direction: kind === "session_charge" ? "debit" : "credit",
      kind,
      reference: {
        type: kind === "free_credit" ? "grant" : kind === "session_refund" ? "session_refund" : "session",
        id: completed.id,
      },
      createdAt: new Date(Date.UTC(2026, 9, 7, 6, 0) - n * 3_600_000).toISOString(),
    };
  });
  return { entries, nextCursor: page === 0 ? "page-2" : null };
}
