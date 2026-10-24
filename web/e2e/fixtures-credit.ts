import { NOW } from "./fixtures";

/**
 * Invented data for the Credit page's money in (ADR 0039): the renter's billing details, a ₹500 top-up by UPI with
 * its receipt, and what of it can be refunded. Every figure follows from the top-up, and the wallet a test pairs it
 * with (`withBought`) adds that top-up to the free credit the account already has.
 */
const ago = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();

export const TOPUP_PAISE = 50_000;
export const TOPUP_ID = "01a1350a-0000-7000-8000-000000071001";

export const STATES = [
  { code: "27", name: "Maharashtra" },
  { code: "29", name: "Karnataka" },
  { code: "33", name: "Tamil Nadu" },
];

export const billingDetails = {
  details: { stateCode: "29", gstin: null, businessName: null },
  gstPercent: 0,
  pricesIncludeGst: true,
  states: STATES,
};

export const noBillingDetails = { ...billingDetails, details: null };

export const receipt = {
  id: "01a1350a-0000-7000-8000-000000072001",
  number: "IDL2627-000042",
  kind: "receipt",
  title: "Receipt",
  issuedAt: ago(3 * 24 * 60),
  taxablePaise: TOPUP_PAISE,
  taxPaise: 0,
  totalPaise: TOPUP_PAISE,
  topUpId: TOPUP_ID,
  creditNoteFor: null,
};

export const invoices = { invoices: [receipt] };
export const noInvoices = { invoices: [] };

export const refundRequests = { refundablePaise: TOPUP_PAISE, requests: [] };
export const noRefunds = { refundablePaise: 0, requests: [] };

/** A wallet with the top-up added to it, as bought credit. */
export function withBought<W extends { balancePaise: number; availablePaise: number; boughtPaise: number }>(
  wallet: W,
): W {
  return {
    ...wallet,
    balancePaise: wallet.balancePaise + TOPUP_PAISE,
    availablePaise: wallet.availablePaise + TOPUP_PAISE,
    boughtPaise: wallet.boughtPaise + TOPUP_PAISE,
  };
}

/** A top-up as the server answers it: `pending` until the captured payment is fetched, then `paid`. */
export const topUp = (status: "pending" | "paid" | "failed", amountPaise = TOPUP_PAISE) => ({
  id: TOPUP_ID,
  amountPaise,
  currency: "INR",
  orderId: "order_Fk000000000101",
  keyId: "rzp_test_idleofake00001",
  status,
  createdAt: NOW.toISOString(),
  paidAt: status === "paid" ? NOW.toISOString() : null,
  method: status === "pending" ? null : "upi",
  cardNetwork: null,
  cardLast4: null,
  failureReason: status === "failed" ? "Payment failed because the UPI ID is invalid." : null,
});

/**
 * A stand-in for Checkout's script in the screen tests: Razorpay's documented shape (`new Razorpay(options)`, `.on`,
 * `.open()`), answering at once with success, failure or a close.
 */
export const checkoutStub = (outcome: "paid" | "failed" | "closed") => `
window.Razorpay = function (options) { this.options = options; this.failed = []; };
window.Razorpay.prototype.on = function (event, handler) { if (event === "payment.failed") this.failed.push(handler); };
window.Razorpay.prototype.open = function () {
  var self = this;
  setTimeout(function () {
    ${
      outcome === "paid"
        ? `self.options.handler({ razorpay_payment_id: "pay_Fk000000000102", razorpay_order_id: self.options.order_id, razorpay_signature: "signature" });`
        : outcome === "failed"
          ? `self.failed.forEach(function (handler) { handler({ error: { description: "Payment failed because the UPI ID is invalid." } }); });`
          : `self.options.modal.ondismiss();`
    }
  }, 50);
};
`;
