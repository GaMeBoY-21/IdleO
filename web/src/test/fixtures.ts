import type { UseQueryResult } from "@tanstack/react-query";
import type { Session, Wallet } from "@/api/queries";

/** A query result in a given state, for rendering a view without a server. */
export function queryResult<T>(state: Partial<UseQueryResult<T>>): UseQueryResult<T> {
  return {
    data: undefined,
    error: null,
    isPending: false,
    isError: false,
    isRefetchError: false,
    isFetching: false,
    dataUpdatedAt: Date.UTC(2026, 9, 7, 6, 34),
    refetch: async () => ({}) as never,
    ...state,
  } as UseQueryResult<T>;
}

export const wallet: Wallet = { balancePaise: 50_000, reservedPaise: 60, availablePaise: 49_940 };

export function session(overrides: Partial<Session> = {}): Session {
  return {
    id: "01a11300-0000-7000-8000-000000000001",
    state: "RUNNING",
    attempt: 1,
    errorCode: null,
    templateId: "cuda-smoke-test",
    hostId: "01a11300-0000-7000-8000-0000000000aa",
    pricePaisePerMin: 2,
    maxPricePaisePerMin: 5,
    maxSpendPaise: 10_000,
    vramNeededMb: 1024,
    maxRuntimeSeconds: 300,
    reservedPaise: 60,
    chargedPaise: 0,
    accruedPaise: 4,
    lowCredit: false,
    settled: false,
    createdAt: "2026-10-07T06:30:00Z",
    startedAt: "2026-10-07T06:30:05Z",
    endedAt: null,
    gpu: { model: "NVIDIA GeForce RTX 4080 Laptop GPU", vramMb: 12_282 },
    params: {},
    input: null,
    checkpoints: [],
    events: [
      { state: "REQUESTED", reason: "requested", at: "2026-10-07T06:30:00Z" },
      { state: "RUNNING", reason: "running", at: "2026-10-07T06:30:05Z" },
    ],
    ...overrides,
  };
}
