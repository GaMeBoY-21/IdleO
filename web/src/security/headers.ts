import type { BuildEnv } from "../build-settings.ts";

/**
 * The app's security headers (ADR 0018), in one place. The Content Security Policy also goes into every page
 * as a <meta> tag, so it holds on a host that sets no headers; the rest need a header, so the build writes them
 * to dist/headers.json for the host, and scripts/serve.ts serves them.
 *
 * Scripts: only our own files and the inline scripts whose hashes the build computed; never 'unsafe-inline'.
 */
export function contentSecurityPolicy(env: BuildEnv, scriptHashes: string[]): string {
  const origin = (url: string | undefined) => (url ? new URL(url).origin : null);
  const firebase = env.VITE_AUTH === "firebase";
  const script = [
    "'self'",
    ...scriptHashes.map((hash) => `'${hash}'`),
    ...(firebase ? ["https://apis.google.com"] : []),
  ];
  const connect = [
    "'self'",
    origin(env.VITE_API_URL),
    // Inputs are uploaded straight to storage through pre-signed links (ADR 0019).
    origin(env.VITE_STORAGE_URL),
    ...(firebase
      ? ["https://identitytoolkit.googleapis.com", "https://securetoken.googleapis.com"]
      : [origin(env.VITE_OIDC_ISSUER)]),
  ].filter((source): source is string => source !== null);
  const frame =
    firebase && env.VITE_FIREBASE_AUTH_DOMAIN ? [`https://${env.VITE_FIREBASE_AUTH_DOMAIN}`] : ["'none'"];
  const directives = [
    ["default-src", "'self'"],
    ["script-src", ...script],
    // Radix positions menus with inline style attributes; styles can't run code.
    ["style-src", "'self'", "'unsafe-inline'"],
    ["img-src", "'self'", "data:"],
    ["font-src", "'self'"],
    ["connect-src", ...connect],
    ["frame-src", ...frame],
    ["object-src", "'none'"],
    ["base-uri", "'none'"],
    ["form-action", "'self'"],
    ...((env.IDLEO_DEPLOYMENT ?? "local") === "local" ? [] : [["upgrade-insecure-requests"]]),
  ];
  return directives.map((parts) => parts.join(" ")).join("; ");
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
