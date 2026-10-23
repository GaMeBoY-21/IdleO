import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, apiFetch, data } from "./client";
import { ApiError } from "./errors";
import { keys } from "./queries";
import type { components } from "./schema.gen";
import { config } from "@/config";

export type TopUp = components["schemas"]["TopUpResponse"];
export type Invoice = components["schemas"]["InvoiceSummary"];
export type BillingDetails = components["schemas"]["BillingDetailsResponse"];
export type RefundRequests = components["schemas"]["RefundList"];

/** How often a top-up still being confirmed is asked about again (ADR 0039). */
export const TOP_UP_POLL_MS = 2_000;

export const walletKeys = {
  billingDetails: ["billing-details"] as const,
  invoices: ["invoices"] as const,
  refunds: ["refund-requests"] as const,
  topUp: (id: string) => ["top-up", id] as const,
};

/** The renter's state, GSTIN and business name for their invoices, and the GST rate and the states to choose from. */
export function useBillingDetails(enabled = true) {
  return useQuery({
    queryKey: walletKeys.billingDetails,
    enabled,
    queryFn: () => data(api.GET("/v1/wallet/billing-details")),
  });
}

/** Saves them; the cache holds only what the server answered. */
export function useSaveBillingDetails() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { stateCode: string; gstin: string | null; businessName: string | null }) =>
      data(api.PUT("/v1/wallet/billing-details", { body })),
    onSuccess: (saved) => client.setQueryData(walletKeys.billingDetails, saved),
  });
}

/** The renter's invoices, receipts and credit notes, newest first. */
export function useInvoices(enabled = true) {
  return useQuery({
    queryKey: walletKeys.invoices,
    enabled,
    queryFn: () => data(api.GET("/v1/wallet/invoices")),
  });
}

/** Saves one document's PDF, fetched with the renter's token when they choose Download, never before. */
export async function downloadInvoice(invoice: Invoice): Promise<void> {
  const response = await apiFetch(new Request(`${config.apiUrl}/v1/wallet/invoices/${invoice.id}/pdf`));
  if (!response.ok) {
    throw ApiError.of(response.status, await response.json().catch(() => undefined));
  }
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = `${invoice.number}.pdf`;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** What bought credit can go back now, and the renter's refund requests. */
export function useRefundRequests(enabled = true) {
  return useQuery({
    queryKey: walletKeys.refunds,
    enabled,
    queryFn: () => data(api.GET("/v1/wallet/refund-requests")),
  });
}

/** Asks for bought credit back (all of it when no amount): a money ticket opens and an admin answers. */
export function useAskRefund() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { amountPaise?: number; note?: string }) =>
      data(api.POST("/v1/wallet/refund-requests", { body })),
    onSuccess: () => client.invalidateQueries({ queryKey: walletKeys.refunds }),
  });
}

/** Orders a top-up: Razorpay's order, which Checkout then pays. The key belongs to this press of Pay. */
export function orderTopUp(amountPaise: number, idempotencyKey: string): Promise<TopUp> {
  return data(
    api.POST("/v1/wallet/topups", {
      params: { header: { "Idempotency-Key": idempotencyKey } },
      body: { amountPaise },
    }),
  );
}

/** Hands Checkout's answer to the server, which credits the top-up only once it has fetched the captured payment. */
export function confirmTopUp(
  id: string,
  answer: { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string },
): Promise<TopUp> {
  return data(
    api.POST("/v1/wallet/topups/{id}/confirm", {
      params: { path: { id } },
      body: {
        razorpayPaymentId: answer.razorpay_payment_id,
        razorpayOrderId: answer.razorpay_order_id,
        razorpaySignature: answer.razorpay_signature,
      },
    }),
  );
}

export function fetchTopUp(id: string): Promise<TopUp> {
  return data(api.GET("/v1/wallet/topups/{id}", { params: { path: { id } } }));
}

/** Everything a paid top-up changes: the wallet, its history and the documents. */
export function refreshAfterTopUp(client: ReturnType<typeof useQueryClient>): Promise<unknown> {
  return Promise.all([
    client.invalidateQueries({ queryKey: keys.wallet }),
    client.invalidateQueries({ queryKey: keys.history }),
    client.invalidateQueries({ queryKey: walletKeys.invoices }),
    client.invalidateQueries({ queryKey: walletKeys.refunds }),
  ]);
}
