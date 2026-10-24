/**
 * The words for a host's payouts (ADR 0040), in one place: each RazorpayX status as the host reads it, and why the next
 * payout can't be made. A test checks every status the API names has words.
 */
export const PAYOUT_STATUS: Record<string, { label: string; tone: "running" | "danger" | "plain" }> = {
  created: { label: "Sending", tone: "plain" },
  queued: { label: "Queued", tone: "plain" },
  pending: { label: "Waiting", tone: "plain" },
  processing: { label: "On its way", tone: "plain" },
  processed: { label: "Paid", tone: "running" },
  failed: { label: "Didn't go through", tone: "danger" },
  rejected: { label: "Didn't go through", tone: "danger" },
  cancelled: { label: "Didn't go through", tone: "danger" },
  reversed: { label: "Sent back by your bank", tone: "danger" },
};

export function payoutStatus(status: string) {
  return PAYOUT_STATUS[status] ?? { label: status, tone: "plain" as const };
}

/** Why the next payout can't be made, and the link that fixes it. */
export const BLOCKED: Record<string, string> = {
  "no-pan": "Add your PAN and a UPI ID to be paid.",
  "pan-in-review": "We're checking your PAN. You'll be paid once it's verified.",
  "no-upi": "Add a UPI ID to be paid.",
  "upi-in-review": "We're checking the name on your UPI ID. You'll be paid once it's approved.",
  "upi-suspect": "A payout came back from your bank. Check your UPI ID again to be paid.",
  "on-hold": "Your UPI ID changed recently, so payouts wait 48 hours.",
};

export function blockedWords(blockedBy: string | null | undefined): string | null {
  return blockedBy ? (BLOCKED[blockedBy] ?? "You can't be paid yet: check Payout details.") : null;
}

/** "ABCPE1234F": what a PAN looks like, checked again by the server, which says why it's refused. */
export const PAN_SHAPE = /^[A-Z]{3}[PHCFATBLJ][A-Z][0-9]{4}[A-Z]$/;

/** "name@bank": RazorpayX's rule for a UPI ID, letters, digits, dots and hyphens with one @, 3 to 100 long. */
export const UPI_SHAPE = /^[A-Za-z0-9.-]{1,98}@[A-Za-z0-9.-]{1,98}$/;

export function maskedPan(last4: string): string {
  return `••••••${last4}`;
}
