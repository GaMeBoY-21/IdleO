import type { Dispute } from "@/api/trust";
import { formatPaise } from "@/lib/money";

/**
 * The reasons a renter can give for a report (ADR 0028), in the server's order and with its values, and the words for
 * where a report stands. One list, used by the report sheet, the session screen and Account's Reports.
 */
export interface ReportReason {
  value: string;
  title: string;
  detail: string;
}

export const REPORT_REASONS: readonly ReportReason[] = [
  { value: "didnt_start", title: "It didn't start", detail: "It never began running, or took far too long" },
  { value: "failed", title: "It failed", detail: "It stopped with an error or ended too early" },
  { value: "slow", title: "It was slow", detail: "It ran slower than the GPU should have" },
  { value: "host_stopped", title: "The host stopped it", detail: "Its machine went away or stopped the job" },
  {
    value: "wrong_output",
    title: "The output was wrong",
    detail: "Missing, empty or not what the job makes",
  },
  { value: "charged_wrong", title: "The charge looks wrong", detail: "More than it should have cost" },
  { value: "other", title: "Something else", detail: "Anything we haven't listed" },
];

export const REPORT_DETAILS_MAX = 2000;

/** What we promise after a report is sent. */
export const REPLY_PROMISE = "We'll reply within 2 days.";

export const reasonTitle = (value: string): string =>
  REPORT_REASONS.find((reason) => reason.value === value)?.title ?? value.replaceAll("_", " ");

/** Where a report stands, in one headline and one line under it. */
export function reportWords(dispute: Dispute): { headline: string; line: string; resolved: boolean } {
  if (dispute.state === "open") {
    return { headline: "Your report is open", line: REPLY_PROMISE, resolved: false };
  }
  const refund =
    dispute.state === "refunded" && dispute.refundPaise != null
      ? `${formatPaise(dispute.refundPaise)} refunded`
      : null;
  return {
    headline: refund ? `Report resolved: ${refund}` : "Report resolved, no refund",
    line: dispute.resolutionReason ?? "",
    resolved: true,
  };
}
