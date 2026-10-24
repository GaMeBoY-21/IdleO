import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/api/errors";
import {
  TOP_UP_POLL_MS,
  confirmTopUp,
  fetchTopUp,
  orderTopUp,
  refreshAfterTopUp,
  type TopUp,
} from "@/api/wallet";
import { brand } from "@/brand/brand";
import { CheckoutClosed, PaymentFailed, pay } from "./checkout";

/** After this long confirming, the page says it's taking longer and keeps checking. */
export const SLOW_AFTER_MS = 10_000;

/**
 * Where a payment stands (ADR 0039). Credit is "added" only once the server says the top-up is paid: never from
 * Checkout's own success, which only says Razorpay took the payment.
 */
export type Payment =
  | { step: "idle" }
  | { step: "ordering"; amountPaise: number }
  | { step: "paying"; amountPaise: number }
  | { step: "confirming"; amountPaise: number; slow: boolean }
  | { step: "added"; amountPaise: number }
  | { step: "failed"; amountPaise: number; reason: string }
  | { step: "problem"; error: unknown };

/**
 * Pay for credit: order a top-up, open Razorpay's Checkout, hand its answer to the server, and ask the server where the
 * top-up stands every 2 s until it's paid or failed. The key of one press of Pay is kept until it's answered, so a
 * retry after a lost answer orders nothing twice.
 */
export function usePay() {
  const client = useQueryClient();
  const [payment, setPayment] = useState<Payment>({ step: "idle" });
  const pending = useRef<{ amountPaise: number; key: string } | null>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  /**
   * Asks the server every 2 s until the top-up is paid or failed, slow after SLOW_AFTER_MS, still asking, whether or not
   * the confirmation has answered yet: the webhook may credit it first, and a slow confirmation is as slow to the
   * renter as a slow payment. `first` is the confirmation's answer: the top-up, null when it was lost, or why the server
   * refused the payment as not this one.
   */
  const follow = useCallback(
    async (topUp: TopUp, first: Promise<TopUp | null | { refused: string }>) => {
      const started = Date.now();
      const confirmation: { refused?: string; answer?: TopUp; done?: boolean } = {};
      const confirmed = first.then((answered) => {
        if (answered !== null && "refused" in answered) {
          confirmation.refused = answered.refused;
        } else if (answered !== null) {
          confirmation.answer = answered;
        }
        confirmation.done = true;
      });
      let current: TopUp | null = null;
      while (alive.current) {
        if (confirmation.refused !== undefined) {
          setPayment({ step: "failed", amountPaise: topUp.amountPaise, reason: confirmation.refused });
          return;
        }
        current = confirmation.answer?.status === "paid" ? confirmation.answer : current;
        if (current?.status === "paid") {
          await refreshAfterTopUp(client);
          setPayment({ step: "added", amountPaise: topUp.amountPaise });
          return;
        }
        if (current?.status === "failed") {
          setPayment({
            step: "failed",
            amountPaise: topUp.amountPaise,
            reason: current.failureReason ?? "The payment didn't go through.",
          });
          return;
        }
        setPayment({
          step: "confirming",
          amountPaise: topUp.amountPaise,
          slow: Date.now() - started >= SLOW_AFTER_MS,
        });
        // The next look, or the confirmation's answer if it comes first.
        const wait = new Promise((resolve) => setTimeout(resolve, TOP_UP_POLL_MS));
        const answeredBefore = confirmation.done === true;
        await (answeredBefore ? wait : Promise.race([wait, confirmed]));
        if (!answeredBefore && confirmation.done === true) {
          // It has just answered: read that answer first.
          continue;
        }
        current = await fetchTopUp(topUp.id).catch(() => current);
      }
    },
    [client],
  );

  const start = useCallback(
    async (amountPaise: number) => {
      if (pending.current?.amountPaise !== amountPaise) {
        pending.current = { amountPaise, key: crypto.randomUUID() };
      }
      setPayment({ step: "ordering", amountPaise });
      let topUp: TopUp;
      try {
        topUp = await orderTopUp(amountPaise, pending.current.key);
      } catch (error) {
        // The key stays: pressing Pay again for the same amount is the same order.
        setPayment({ step: "problem", error });
        return;
      }
      pending.current = null;
      setPayment({ step: "paying", amountPaise });
      let answer;
      try {
        answer = await pay({
          key: topUp.keyId,
          amount: topUp.amountPaise,
          currency: "INR",
          order_id: topUp.orderId,
          name: brand.name,
          description: "Credit",
        });
      } catch (error) {
        if (error instanceof CheckoutClosed) {
          setPayment({ step: "idle" });
        } else if (error instanceof PaymentFailed) {
          setPayment({ step: "failed", amountPaise, reason: error.message });
        } else {
          setPayment({ step: "problem", error });
        }
        return;
      }
      setPayment({ step: "confirming", amountPaise, slow: false });
      // A confirmation refused as another payment than this top-up's is final; anything else (a lost answer, Razorpay
      // slow to say it's captured) is followed until the server knows, as the webhook may tell it first.
      const confirmed = confirmTopUp(topUp.id, answer).catch((error: unknown) =>
        error instanceof ApiError && error.type === "/problems/payment-not-verified"
          ? { refused: error.message }
          : null,
      );
      await follow(topUp, confirmed);
    },
    [follow],
  );

  const reset = useCallback(() => setPayment({ step: "idle" }), []);
  return { payment, start, reset };
}
