import { readable } from "@/lib/labels";

/**
 * The renter's words for each kind of credit movement in their history (the control plane's WalletController names
 * them; a test reads that list against this one). Only the Credit page shows them, so they load with it.
 */
const KINDS: Record<string, string> = {
  free_credit: "Free credit",
  free_credit_reversed: "Free credit withdrawn",
  session_charge: "Session charge",
  session_refund: "Refund for lost host time",
  dispute_refund: "Refund after your report",
  free_credit_expired: "Free credit expired",
  free_credit_forfeited: "Free credit given up",
  top_up: "Credit added",
  refund_to_payment: "Refunded to where you paid",
  refund_returned: "Refund couldn't be sent; credit back",
  frozen_for_chargeback: "Held for a disputed payment",
  chargeback_settled: "Disputed payment settled",
};

/** Every kind of credit movement with words of its own (a test reads the control plane's list against it). */
export const CREDIT_KINDS: readonly string[] = Object.keys(KINDS);

export const creditKind = (kind: string) => KINDS[kind] ?? readable(kind);
