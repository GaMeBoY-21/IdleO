import type { BuildEnv } from "../build-settings.ts";

/**
 * The origins Razorpay's Checkout uses, by directive (ADR 0039): only the Credit page allows them, and only it loads
 * Checkout. Razorpay publishes no list, so each is here with where it came from; a real test-mode check run with
 * IDLEO_CHECKOUT_CSP=report-only (docs/runbooks/payments.md) collects every one the browser reports, and this list is
 * exactly what that check found.
 */
export const CHECKOUT_ORIGINS: Readonly<Record<CheckoutDirective, readonly string[]>> = {
  // The Checkout script (https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/).
  script: ["https://checkout.razorpay.com"],
  // Checkout's window, served from Razorpay's API host (https://razorpay.com/docs/security/whitelists/); to confirm in
  // the real test-mode check.
  frame: ["https://api.razorpay.com"],
  connect: [],
  img: [],
};

/** What report-only allows while collecting (Razorpay's Cordova guide whitelists its domains this way). */
export const CHECKOUT_COLLECTING = "https://*.razorpay.com";

type CheckoutDirective = "script" | "frame" | "connect" | "img";

/** Which Checkout origins a page allows: none (every page but Credit), or these. */
type CheckoutSources = Readonly<Record<CheckoutDirective, readonly string[]>>;

const NO_CHECKOUT: CheckoutSources = { script: [], frame: [], connect: [], img: [] };

/**
 * The Credit page's Checkout origins (ADR 0039). While collecting (`collecting`, from IDLEO_CHECKOUT_CSP=report-only, a
 * local or staging build's), all of Razorpay's domains, so Checkout runs while a report-only policy names what it used;
 * otherwise a local build allows the fake Razorpay's origin, which serves the fake Checkout and is where it pays, and a
 * deployed one CHECKOUT_ORIGINS. A build without a Checkout script allows nothing.
 */
export function checkoutSources(env: BuildEnv, collecting = false): CheckoutSources {
  if (!env.VITE_CHECKOUT_URL) {
    return NO_CHECKOUT;
  }
  if (collecting) {
    return {
      script: [CHECKOUT_COLLECTING],
      frame: [CHECKOUT_COLLECTING],
      connect: [CHECKOUT_COLLECTING],
      img: [CHECKOUT_COLLECTING],
    };
  }
  if ((env.IDLEO_DEPLOYMENT ?? "local") === "local") {
    const fake = new URL(env.VITE_CHECKOUT_URL).origin;
    return { script: [fake], frame: [], connect: [fake], img: [] };
  }
  return CHECKOUT_ORIGINS;
}

/**
 * The app's security headers (ADR 0018), in one place. The Content Security Policy also goes into every page
 * as a <meta> tag, so it holds on a host that sets no headers; the rest need a header, so the build writes them
 * to dist/headers.json for the host, and scripts/serve.ts serves them. The Credit page's own, with Checkout's origins
 * (`checkout`), goes into its page and dist/path-headers.json (ADR 0039).
 *
 * Scripts: only our own files and the inline scripts whose hashes the build computed; never 'unsafe-inline'.
 */
export function contentSecurityPolicy(
  env: BuildEnv,
  scriptHashes: string[],
  checkout: CheckoutSources = NO_CHECKOUT,
): string {
  const origin = (url: string | undefined) => (url ? new URL(url).origin : null);
  const firebase = env.VITE_AUTH === "firebase";
  const script = [
    "'self'",
    ...scriptHashes.map((hash) => `'${hash}'`),
    ...(firebase ? ["https://apis.google.com"] : []),
    ...checkout.script,
  ];
  const connect = [
    "'self'",
    origin(env.VITE_API_URL),
    // Inputs are uploaded straight to storage through pre-signed links (ADR 0019).
    origin(env.VITE_STORAGE_URL),
    ...(firebase
      ? ["https://identitytoolkit.googleapis.com", "https://securetoken.googleapis.com"]
      : [origin(env.VITE_OIDC_ISSUER)]),
    ...checkout.connect,
  ].filter((source): source is string => source !== null);
  const frames = [
    ...(firebase && env.VITE_FIREBASE_AUTH_DOMAIN ? [`https://${env.VITE_FIREBASE_AUTH_DOMAIN}`] : []),
    ...checkout.frame,
  ];
  const directives = [
    ["default-src", "'self'"],
    ["script-src", ...script],
    // Radix positions menus with inline style attributes; styles can't run code.
    ["style-src", "'self'", "'unsafe-inline'"],
    ["img-src", "'self'", "data:", ...checkout.img],
    ["font-src", "'self'"],
    ["connect-src", ...connect],
    ["frame-src", ...(frames.length > 0 ? frames : ["'none'"])],
    ["object-src", "'none'"],
    ["base-uri", "'none'"],
    ["form-action", "'self'"],
    ...((env.IDLEO_DEPLOYMENT ?? "local") === "local" ? [] : [["upgrade-insecure-requests"]]),
  ];
  return directives.map((parts) => parts.join(" ")).join("; ");
}

/**
 * The Credit page's headers (ADR 0039): its own policy, and while collecting, the strict one as
 * Content-Security-Policy-Report-Only, so the browser names every origin Checkout uses that CHECKOUT_ORIGINS lacks.
 */
export function creditHeaders(env: BuildEnv, csp: string, strictCsp: string): Record<string, string> {
  return {
    ...securityHeaders(csp),
    ...(env.IDLEO_CHECKOUT_CSP === "report-only"
      ? { "Content-Security-Policy-Report-Only": `${strictCsp}; frame-ancestors 'none'` }
      : {}),
  };
}

export function securityHeaders(csp: string): Record<string, string> {
  return {
    // frame-ancestors only works as a header, never in a <meta> tag.
    "Content-Security-Policy": `${csp}; frame-ancestors 'none'`,
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()",
    "X-Content-Type-Options": "nosniff",
    // The Google sign-in popup must be able to report back to the page that opened it.
    "Cross-Origin-Opener-Policy": "same-origin-allow-popups",
  };
}
