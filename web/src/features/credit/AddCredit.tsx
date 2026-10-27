import { useState } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import type { BillingDetails } from "@/api/wallet";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { formatPaise, parseRupees } from "@/lib/money";
import { ApiError } from "@/api/errors";
import { problemMessage } from "@/lib/problems";
import { CheckoutUnavailable, checkoutAvailable } from "./checkout";
import { usePay, type Payment } from "./usePay";

/** The packs offered first, in paise; any other amount can be typed. */
export const PACKS = [10_000, 50_000, 100_000, 200_000] as const;

/** "₹500": a pack's amount, whole rupees. */
const whole = (paise: number) => formatPaise(paise).slice(0, -".00".length);

/**
 * Add credit (ADR 0039): a pack or an amount, then Pay, which opens Razorpay's Checkout. What it says under the button
 * is always the server's word: "Adding ₹500…" until the server has the captured payment, then "₹500 added". The line
 * keeps its height whatever it says, so nothing below it moves.
 */
export function AddCredit({ details }: { details: UseQueryResult<BillingDetails> }) {
  const { payment, start, reset } = usePay();
  const [pack, setPack] = useState<number | null>(PACKS[1]);
  const [typed, setTyped] = useState("");
  const typedPaise = typed === "" ? null : parseRupees(typed);
  const amountPaise = pack ?? (typedPaise !== null && Number.isInteger(typedPaise / 100) ? typedPaise : null);
  const amountError =
    pack === null && typed !== "" && amountPaise === null ? "Whole rupees, more than nothing." : undefined;
  const stateSet = Boolean(details.data?.details?.stateCode);
  const busy = payment.step === "ordering" || payment.step === "paying" || payment.step === "confirming";
  const available = checkoutAvailable();

  const choose = (paise: number | null) => {
    setPack(paise);
    if (!busy) {
      reset();
    }
  };

  return (
    <Tile as="section" aria-labelledby="add-credit" className="flex flex-col gap-4">
      <h2 id="add-credit" className="text-title font-semibold">
        Add credit
      </h2>
      <div role="group" aria-label="Amount" className="flex flex-wrap gap-2 tabular">
        {PACKS.map((paise) => (
          <button
            key={paise}
            type="button"
            aria-pressed={pack === paise}
            disabled={busy}
            className={chip(pack === paise)}
            onClick={() => choose(paise)}
          >
            {whole(paise)}
          </button>
        ))}
        <button
          type="button"
          aria-pressed={pack === null}
          disabled={busy}
          className={chip(pack === null)}
          onClick={() => choose(null)}
        >
          Another amount
        </button>
      </div>
      {pack === null && (
        <Field
          label="Amount, in ₹"
          type="number"
          inputMode="numeric"
          step="1"
          min="1"
          value={typed}
          disabled={busy}
          error={amountError}
          hint="Whole rupees."
          onChange={(event) => {
            setTyped(event.target.value);
            reset();
          }}
        />
      )}
      <InvoiceLine details={details} />
      <Button
        block
        large
        busy={busy}
        disabled={!available || !stateSet || amountPaise === null}
        onClick={() => amountPaise !== null && void start(amountPaise)}
      >
        {amountPaise === null ? "Pay" : `Pay ${formatPaise(amountPaise)}`}
      </Button>
      <PaymentLine payment={payment} available={available} />
    </Tile>
  );
}

function chip(selected: boolean) {
  return cn(
    "flex min-h-touch items-center rounded-full px-4 text-small font-semibold transition-colors disabled:opacity-60",
    selected ? "bg-primary text-primary-fg" : "bg-canvas hover:bg-line",
  );
}

/** Who the receipt or invoice will be for: one line, whatever it says. */
function InvoiceLine({ details }: { details: UseQueryResult<BillingDetails> }) {
  if (!details.data) {
    return details.isError ? (
      <p className="min-h-4.5 text-caption text-text-2">We couldn&apos;t load your billing details.</p>
    ) : (
      <div className="flex min-h-4.5 items-center">
        <SkeletonLine size="caption" width="w-2/3" />
      </div>
    );
  }
  const saved = details.data.details;
  const state = details.data.states.find((choice) => choice.code === saved?.stateCode);
  return (
    <p className="min-h-4.5 text-caption text-text-2">
      {saved && state ? (
        <>
          Your {details.data.gstPercent > 0 ? "invoice" : "receipt"} goes to {saved.businessName ?? "you"} in{" "}
          {state.name}
          {saved.gstin ? `, GSTIN ${saved.gstin}` : ""}.
        </>
      ) : (
        <>
          Choose your state under{" "}
          <a href="#billing-details" className="font-semibold text-text underline">
            Billing details
          </a>{" "}
          first.
        </>
      )}
    </p>
  );
}

/** Where the payment stands, in the server's words; two lines tall always. */
function PaymentLine({ payment, available }: { payment: Payment; available: boolean }) {
  const text = (() => {
    switch (payment.step) {
      case "idle":
        return available
          ? "Paid through Razorpay by UPI, card or net banking. We never see your card or UPI details."
          : "Payments aren't set up on this server.";
      case "ordering":
        return "Starting the payment…";
      case "paying":
        return "Finish paying in Razorpay's window.";
      case "confirming":
        return payment.slow
          ? `Adding ${formatPaise(payment.amountPaise)}… Razorpay is taking longer than usual to confirm it. We'll keep checking; you can leave this page.`
          : `Adding ${formatPaise(payment.amountPaise)}…`;
      case "added":
        return `${formatPaise(payment.amountPaise)} added.`;
      case "failed":
        return `${payment.reason} No credit was added.`;
      case "problem":
        return problemWords(payment.error);
    }
  })();
  const problem = payment.step === "failed" || payment.step === "problem";
  return (
    <p
      role={problem ? "alert" : "status"}
      className={cn(
        "min-h-9 text-small",
        problem ? "text-danger" : payment.step === "added" ? "font-semibold text-running" : "text-text-2",
      )}
    >
      {text}
    </p>
  );
}

/** Why a payment couldn't start: the server's own detail for the limits, which says what the renter can add. */
function problemWords(error: unknown): string {
  if (error instanceof CheckoutUnavailable) {
    return "Razorpay's payment window couldn't be loaded. Check your connection, then reload this page.";
  }
  if (
    error instanceof ApiError &&
    (error.type === "/problems/top-up-out-of-limits" ||
      error.type === "/problems/too-many-failed-payments") &&
    error.detail
  ) {
    return error.detail;
  }
  return problemMessage(error);
}
