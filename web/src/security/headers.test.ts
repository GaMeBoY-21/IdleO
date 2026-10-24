// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  CHECKOUT_COLLECTING,
  CHECKOUT_ORIGINS,
  checkoutSources,
  contentSecurityPolicy,
  creditHeaders,
  securityHeaders,
} from "./headers";

const directive = (csp: string, name: string) =>
  csp.split("; ").find((part) => part.startsWith(`${name} `)) ?? "";

describe("the security headers", () => {
  const local = contentSecurityPolicy(
    {
      VITE_AUTH: "oidc-mock",
      VITE_API_URL: "http://localhost:8080",
      VITE_STORAGE_URL: "http://localhost:9100",
      VITE_OIDC_ISSUER: "http://localhost:8099/idleo-local",
    },
    ["sha256-abc"],
  );
  const production = contentSecurityPolicy(
    {
      IDLEO_DEPLOYMENT: "production",
      VITE_AUTH: "firebase",
      VITE_API_URL: "https://api.example.invalid/",
      VITE_STORAGE_URL: "https://storage.example.invalid/idleo",
      VITE_FIREBASE_AUTH_DOMAIN: "placeholder.firebaseapp.com",
    },
    ["sha256-abc"],
  );

  it("allow scripts only from this origin and the hashed inline scripts, never unsafe-inline", () => {
    for (const csp of [local, production]) {
      expect(directive(csp, "script-src")).toContain("'self' 'sha256-abc'");
      expect(directive(csp, "script-src")).not.toContain("unsafe");
      expect(csp).toContain("object-src 'none'");
      expect(csp).toContain("base-uri 'none'");
    }
  });

  it("connect only to the API, storage for uploads, and the build's sign-in service", () => {
    expect(directive(local, "connect-src")).toBe(
      "connect-src 'self' http://localhost:8080 http://localhost:9100 http://localhost:8099",
    );
    expect(directive(production, "connect-src")).toBe(
      "connect-src 'self' https://api.example.invalid https://storage.example.invalid https://identitytoolkit.googleapis.com https://securetoken.googleapis.com",
    );
    expect(directive(local, "frame-src")).toBe("frame-src 'none'");
    expect(directive(production, "frame-src")).toBe("frame-src https://placeholder.firebaseapp.com");
    expect(production).toContain("upgrade-insecure-requests");
    expect(local).not.toContain("upgrade-insecure-requests");
  });

  it("send the headers a meta tag can't", () => {
    const headers = securityHeaders(local);
    expect(headers["Content-Security-Policy"]).toContain("frame-ancestors 'none'");
    expect(headers["X-Content-Type-Options"]).toBe("nosniff");
    expect(headers["Referrer-Policy"]).toBe("strict-origin-when-cross-origin");
    expect(headers["Permissions-Policy"]).toContain("camera=()");
  });

  it("let only the Credit page load Checkout: the fake's origin locally, Razorpay's listed origins deployed", () => {
    const env = {
      IDLEO_DEPLOYMENT: "production",
      VITE_AUTH: "firebase",
      VITE_API_URL: "https://api.example.invalid/",
      VITE_STORAGE_URL: "https://storage.example.invalid/idleo",
      VITE_FIREBASE_AUTH_DOMAIN: "placeholder.firebaseapp.com",
      VITE_CHECKOUT_URL: "https://checkout.razorpay.com/v1/checkout.js",
    };
    expect(production).not.toContain("razorpay");
    const credit = contentSecurityPolicy(env, ["sha256-abc"], checkoutSources(env));
    expect(directive(credit, "script-src")).toContain(CHECKOUT_ORIGINS.script.join(" "));
    expect(directive(credit, "frame-src")).toBe(
      ["frame-src https://placeholder.firebaseapp.com", ...CHECKOUT_ORIGINS.frame].join(" "),
    );
    expect(credit).not.toContain(CHECKOUT_COLLECTING);
    expect(creditHeaders(env, credit, credit)).not.toHaveProperty("Content-Security-Policy-Report-Only");

    const localEnv = {
      VITE_AUTH: "oidc-mock",
      VITE_API_URL: "http://localhost:8080",
      VITE_STORAGE_URL: "http://localhost:9100",
      VITE_OIDC_ISSUER: "http://localhost:8099/idleo-local",
    };
    const fake = { ...localEnv, VITE_CHECKOUT_URL: "http://localhost:8091/v1/checkout.js" };
    const localCredit = contentSecurityPolicy(fake, [], checkoutSources(fake));
    expect(directive(localCredit, "script-src")).toBe("script-src 'self' http://localhost:8091");
    expect(directive(localCredit, "connect-src")).toContain("http://localhost:8091");
    expect(checkoutSources(localEnv)).toEqual({ script: [], frame: [], connect: [], img: [] });
  });

  it("collect Checkout's origins in report-only: Razorpay's domains allowed, the strict list reported", () => {
    const env = {
      IDLEO_DEPLOYMENT: "staging",
      VITE_AUTH: "firebase",
      VITE_API_URL: "https://api.example.invalid/",
      VITE_STORAGE_URL: "https://storage.example.invalid/idleo",
      VITE_CHECKOUT_URL: "https://checkout.razorpay.com/v1/checkout.js",
      IDLEO_CHECKOUT_CSP: "report-only",
    };
    const allowing = contentSecurityPolicy(env, [], checkoutSources(env, true));
    const strict = contentSecurityPolicy(env, [], checkoutSources(env));
    for (const name of ["script-src", "frame-src", "connect-src", "img-src"]) {
      expect(directive(allowing, name)).toContain(CHECKOUT_COLLECTING);
    }
    // A local build collects the same way, for the check on a Mac (docs/runbooks/payments.md).
    expect(checkoutSources({ ...env, IDLEO_DEPLOYMENT: "local" }, true).frame).toEqual([CHECKOUT_COLLECTING]);
    const headers = creditHeaders(env, allowing, strict);
    expect(headers["Content-Security-Policy"]).toContain(CHECKOUT_COLLECTING);
    expect(headers["Content-Security-Policy-Report-Only"]).toBe(`${strict}; frame-ancestors 'none'`);
  });
});
