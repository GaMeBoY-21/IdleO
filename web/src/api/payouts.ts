import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, apiFetch, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";
import { config } from "@/config";
import { deletionKeys } from "./privacy";

export type PayoutSummary = components["schemas"]["PayoutSummary"];
export type PendingEarning = components["schemas"]["PendingEarning"];
export type PayoutView = components["schemas"]["PayoutView"];
export type PayoutDetails = components["schemas"]["PayoutDetails"];
export type PanView = components["schemas"]["PanView"];
export type UpiView = components["schemas"]["UpiView"];

/**
 * A host's payouts (ADR 0040): what's pending and available, their payouts, and where payouts go. Money is only what
 * the server last returned: nothing is counted or moved on the client.
 */
export const PAYOUTS_PAGE = 20;
/** How often a UPI ID check RazorpayX hasn't answered yet is asked about again. */
export const CHECKING_POLL_MS = 3_000;

export const payoutKeys = {
  summary: ["payouts", "summary"] as const,
  history: ["payouts", "history"] as const,
  details: ["payout-details"] as const,
};

export function usePayoutSummary(enabled = true) {
  return useQuery({
    queryKey: payoutKeys.summary,
    enabled,
    queryFn: () => data(api.GET("/v1/payouts/summary")),
  });
}

/** The host's payouts, newest first, a page at a time. */
export function usePayoutHistory(enabled = true) {
  return useInfiniteQuery({
    queryKey: payoutKeys.history,
    enabled,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/payouts", {
          params: { query: { limit: PAYOUTS_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? null,
  });
}

/** Where payouts go; asked again every few seconds while a check is still with the bank. */
export function usePayoutDetails(enabled = true) {
  return useQuery({
    queryKey: payoutKeys.details,
    enabled,
    queryFn: () => data(api.GET("/v1/payout-details")),
    refetchInterval: (query) =>
      query.state.data?.latestCheck?.state === "checking" ? CHECKING_POLL_MS : false,
  });
}

/** Gives the PAN and the name as on it; the cache holds what the server answered. */
export function useSubmitPan() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { pan: string; name: string }) => data(api.PUT("/v1/payout-details/pan", { body })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: payoutKeys.details });
      void client.invalidateQueries({ queryKey: payoutKeys.summary });
    },
  });
}

/**
 * Asks RazorpayX who a UPI ID belongs to. `idempotencyKey` belongs to the UPI ID as typed, so a repeat after a lost
 * answer is the same check, not a second penny drop.
 */
export function useCheckUpi() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (variables: { vpa: string; idempotencyKey: string }) =>
      data(
        api.POST("/v1/payout-details/upi-checks", {
          params: { header: { "Idempotency-Key": variables.idempotencyKey } },
          body: { vpa: variables.vpa },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: payoutKeys.details }),
  });
}

/** Makes a check where payouts go: needs a sign-in in the last 10 minutes (`fresh-sign-in-required` otherwise). */
export function useConfirmUpi() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      data(api.POST("/v1/payout-details/upi-checks/{id}/confirm", { params: { path: { id } } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: payoutKeys.details });
      void client.invalidateQueries({ queryKey: payoutKeys.summary });
    },
  });
}

/** Gives up every unpaid earning in writing, to delete the account without a UPI ID. */
export function useForfeit() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (statement: string) => data(api.POST("/v1/payouts/forfeit", { body: { statement } })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: payoutKeys.summary });
      void client.invalidateQueries({ queryKey: deletionKeys.standing });
    },
  });
}

/** Saves the year's statement, fetched with the host's token when they choose it, never before. */
export async function downloadStatement(year: string, format: "pdf" | "csv"): Promise<void> {
  const response = await apiFetch(
    new Request(`${config.apiUrl}/v1/payouts/statement?year=${encodeURIComponent(year)}&format=${format}`),
  );
  if (!response.ok) {
    throw ApiError.of(response.status, await response.json().catch(() => undefined));
  }
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `idleo-payouts-${year}.${format}`;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
