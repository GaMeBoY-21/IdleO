// @vitest-environment node
import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, securityHeaders } from "./headers";

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
});
