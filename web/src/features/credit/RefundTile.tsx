import { lazy, Suspense, useState } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import type { Wallet } from "@/api/queries";
import type { RefundRequests } from "@/api/wallet";
import { Button } from "@/components/ui/Button";
import { Tile } from "@/components/ui/Tile";
import { formatPaise } from "@/lib/money";
import { formatDay } from "@/lib/time";

type Request = RefundRequests["requests"][number];

/** Where a request stands, in the renter's words. */
export function refundWords(request: Request): string {
  const asked =
    request.requestedPaise != null ? formatPaise(request.requestedPaise) : "all your unused bought credit";
  const ticket = request.ticketNumber != null ? ` (message #${request.ticketNumber})` : "";
  switch (request.status) {
    case "requested":
      return `You asked for ${asked} back on ${formatDay(request.requestedAt)}${ticket}. We'll answer by email.`;
    case "refunding":
      return `${formatPaise(request.approvedPaise ?? 0)} is on its way back to where you paid from.`;
    case "refunded":
      return `${formatPaise(request.approvedPaise ?? 0)} went back to where you paid from${request.finishedAt ? ` on ${formatDay(request.finishedAt)}` : ""}.`;
    case "declined":
      return `We didn't refund it: ${request.decision ?? "write to us if you'd like to know why."}`;
    case "failed":
      return "Razorpay couldn't send it back, so the credit is with you again. Write to us and we'll sort it out.";
    default:
      return "";
  }
}

/**
 * Refund unused credit (ADR 0039): bought credit can go back, only to where it was paid from; free credit never does.
 * Drawn only when there's something to say: bought credit, a request, or credit held for a disputed payment.
 */
const AskForm = lazy(() => import("./RefundAskForm").then((module) => ({ default: module.AskForm })));

export function RefundTile({
  wallet,
  refunds,
}: {
  wallet: UseQueryResult<Wallet>;
  refunds: UseQueryResult<RefundRequests>;
}) {
  const [asking, setAsking] = useState(false);
  if (!wallet.data || !refunds.data) {
    return null;
  }
  const latest = refunds.data.requests[0];
  const open = latest && (latest.status === "requested" || latest.status === "refunding");
  const { refundablePaise } = refunds.data;
  const { frozenPaise, boughtPaise } = wallet.data;
  if (boughtPaise === 0 && frozenPaise === 0 && !latest) {
    return null;
  }
  return (
    <Tile as="section" aria-labelledby="refund-title" className="flex flex-col gap-3">
      <h2 id="refund-title" className="font-title text-display-card">
        Refund unused credit
      </h2>
      <p className="text-small text-text-2">
        {refundablePaise > 0
          ? `${formatPaise(refundablePaise)} of the credit you bought can go back to where you paid from. Free credit can't.`
          : "Credit you bought can go back to where you paid from, once it isn't on hold. Free credit can't."}
      </p>
      {latest && <p className="text-small">{refundWords(latest)}</p>}
      {frozenPaise > 0 && (
        <p className="text-small">
          {formatPaise(frozenPaise)} is held while your bank looks at a payment you disputed.
        </p>
      )}
      {asking ? (
        <Suspense fallback={null}>
          <AskForm refundablePaise={refundablePaise} onDone={() => setAsking(false)} />
        </Suspense>
      ) : (
        !open &&
        refundablePaise > 0 && (
          <Button variant="ghost" className="self-start" onClick={() => setAsking(true)}>
            Ask for a refund
          </Button>
        )
      )}
    </Tile>
  );
}
