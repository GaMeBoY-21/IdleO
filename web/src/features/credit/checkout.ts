/**
 * Razorpay's Checkout script (VITE_CHECKOUT_URL): Razorpay's own in a deployed build, the fake Razorpay's on the local
 * stack, and none (no payments) when a local build leaves it out. Read here, not in config.ts, so it's in the Credit
 * page's own file and never the first load's.
 */
const CHECKOUT_URL = import.meta.env.VITE_CHECKOUT_URL ?? "";

/**
 * Razorpay's Standard Checkout (ADR 0039; https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/),
 * loaded only when the renter presses Pay, so it's never in the first load. Card and UPI details go into Checkout's own
 * window and never reach this page or our server; we get back the documented ids and signature.
 */
export interface CheckoutAnswer {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

export interface CheckoutFailure {
  error: { code?: string; description?: string; metadata?: { payment_id?: string; order_id?: string } };
}

export interface CheckoutOptions {
  key: string;
  amount: number;
  currency: "INR";
  order_id: string;
  name: string;
  description: string;
  handler: (answer: CheckoutAnswer) => void;
  modal: { ondismiss: () => void };
}

interface Checkout {
  on(event: "payment.failed", handler: (failure: CheckoutFailure) => void): void;
  open(): void;
}

type CheckoutConstructor = new (options: CheckoutOptions) => Checkout;

declare global {
  interface Window {
    Razorpay?: CheckoutConstructor;
  }
}

let loading: Promise<CheckoutConstructor> | null = null;

/** Whether this build can take payments: it names a Checkout script (VITE_CHECKOUT_URL). */
export const checkoutAvailable = (): boolean => CHECKOUT_URL !== "";

/** Checkout's constructor, its script added to the page the first time it's asked for. */
export function loadCheckout(): Promise<CheckoutConstructor> {
  if (window.Razorpay) {
    return Promise.resolve(window.Razorpay);
  }
  loading ??= new Promise<CheckoutConstructor>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = CHECKOUT_URL;
    script.async = true;
    script.onload = () => (window.Razorpay ? resolve(window.Razorpay) : reject(new CheckoutUnavailable()));
    script.onerror = () => {
      loading = null;
      script.remove();
      reject(new CheckoutUnavailable());
    };
    document.head.append(script);
  });
  return loading;
}

/**
 * Opens Checkout for an order. Resolves with Checkout's answer, or rejects with Razorpay's own description of why the
 * payment failed, or with `"dismissed"` when the renter closed it without paying.
 */
export async function pay(options: Omit<CheckoutOptions, "handler" | "modal">): Promise<CheckoutAnswer> {
  const Razorpay = await loadCheckout();
  return new Promise<CheckoutAnswer>((resolve, reject) => {
    const checkout = new Razorpay({
      ...options,
      handler: resolve,
      modal: { ondismiss: () => reject(new CheckoutClosed()) },
    });
    checkout.on("payment.failed", (failure) =>
      reject(new PaymentFailed(failure.error.description ?? "The payment didn't go through.")),
    );
    checkout.open();
  });
}

/** The renter closed Checkout without paying. */
export class CheckoutClosed extends Error {
  constructor() {
    super("Checkout was closed");
  }
}

/** Razorpay said the payment failed, in its words. */
export class PaymentFailed extends Error {}

/** Checkout's script couldn't be loaded: the connection, or a page whose policy doesn't allow it. */
export class CheckoutUnavailable extends Error {
  constructor() {
    super("Razorpay's Checkout couldn't be loaded");
  }
}
