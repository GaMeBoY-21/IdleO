// @vitest-environment node
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { buildSettingsProblems, siteSettingsProblems } from "./build-settings";

const local = {
  IDLEO_DEPLOYMENT: "local",
  VITE_API_URL: "http://localhost:8080",
  VITE_STORAGE_URL: "http://localhost:9100",
  VITE_AUTH: "oidc-mock",
  VITE_OIDC_ISSUER: "http://localhost:8099/idleo-local",
  VITE_OIDC_CLIENT_ID: "idleo-local",
};
const production = {
  IDLEO_DEPLOYMENT: "production",
  VITE_API_URL: "https://api.example.invalid",
  VITE_STORAGE_URL: "https://storage.example.invalid",
  IDLEO_SITE_URL: "https://site.example.invalid",
  IDLEO_APP_URL: "https://app.example.invalid",
  VITE_AUTH: "firebase",
  VITE_FIREBASE_API_KEY: "placeholder",
  VITE_FIREBASE_AUTH_DOMAIN: "placeholder.firebaseapp.com",
  VITE_FIREBASE_PROJECT_ID: "placeholder",
  VITE_CHECKOUT_URL: "https://checkout.razorpay.com/v1/checkout.js",
};

describe("the build settings", () => {
  it("let a local build use the mock sign-in, and production Firebase", () => {
    expect(buildSettingsProblems(local)).toEqual([]);
    expect(buildSettingsProblems(production)).toEqual([]);
  });

  it("need the public site's address outside a local build, and an https one", () => {
    const without = { ...production, IDLEO_SITE_URL: undefined };
    expect(buildSettingsProblems(without)).toEqual([
      "a production build needs IDLEO_SITE_URL, the public site's address",
    ]);
    expect(buildSettingsProblems({ ...production, IDLEO_SITE_URL: "http://localhost:3000" })).toEqual([
      "a production build needs an https site address that isn't on this machine",
    ]);
    expect(buildSettingsProblems(local)).toEqual([]);
  });

  it("need the app's address outside a local build, and an https one", () => {
    expect(buildSettingsProblems({ ...production, IDLEO_APP_URL: undefined })).toEqual([
      "a production build needs IDLEO_APP_URL, the app's address",
    ]);
    expect(buildSettingsProblems({ ...production, IDLEO_APP_URL: "http://localhost:3000" })).toEqual([
      "a production build needs an https app address that isn't on this machine",
    ]);
    expect(siteSettingsProblems({ IDLEO_DEPLOYMENT: "production" })).toHaveLength(2);
    expect(siteSettingsProblems({})).toEqual([]);
  });

  it("refuse the mock sign-in adapter outside a local build", () => {
    for (const deployment of ["production", "staging"]) {
      expect(
        buildSettingsProblems({
          ...local,
          IDLEO_DEPLOYMENT: deployment,
          VITE_API_URL: "https://api.example.invalid",
        }),
      ).toContain(`a ${deployment} build can't use the oidc-mock sign-in adapter; use firebase`);
    }
  });

  it("refuse a production API that's plain HTTP or on this machine", () => {
    expect(buildSettingsProblems({ ...production, VITE_API_URL: "http://localhost:8080" })).toContain(
      "a production build needs an https API that isn't on this machine",
    );
  });

  it("need storage for uploads, https outside a local build", () => {
    expect(buildSettingsProblems({ ...local, VITE_STORAGE_URL: undefined })).toContain(
      "VITE_STORAGE_URL is missing",
    );
    expect(buildSettingsProblems({ ...production, VITE_STORAGE_URL: "http://localhost:9100" })).toContain(
      "a production build needs https storage that isn't on this machine",
    );
  });

  it("load only Razorpay's own Checkout outside a local build, and enforce its policy in production", () => {
    expect(
      buildSettingsProblems({ ...local, VITE_CHECKOUT_URL: "http://localhost:8091/v1/checkout.js" }),
    ).toEqual([]);
    expect(buildSettingsProblems({ ...production, VITE_CHECKOUT_URL: undefined })).toContain(
      "a production build needs VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js, Razorpay's own Checkout",
    );
    expect(
      buildSettingsProblems({
        ...production,
        VITE_CHECKOUT_URL: "https://checkout.example.invalid/v1/checkout.js",
      }),
    ).toHaveLength(1);
    expect(buildSettingsProblems({ ...production, IDLEO_CHECKOUT_CSP: "report-only" })).toEqual([
      "a production build enforces the Credit page's policy; IDLEO_CHECKOUT_CSP=report-only is for staging",
    ]);
    expect(
      buildSettingsProblems({
        ...production,
        IDLEO_DEPLOYMENT: "staging",
        IDLEO_CHECKOUT_CSP: "report-only",
      }),
    ).toEqual([]);
    expect(buildSettingsProblems({ ...local, IDLEO_CHECKOUT_CSP: "off" })).toEqual([
      "IDLEO_CHECKOUT_CSP must be enforce or report-only, not off",
    ]);
  });

  it("need the chosen adapter's settings", () => {
    expect(buildSettingsProblems({ ...production, VITE_FIREBASE_API_KEY: "" })[0]).toMatch(
      /firebase adapter needs/,
    );
    expect(buildSettingsProblems({ ...local, VITE_OIDC_ISSUER: undefined })[0]).toMatch(
      /oidc-mock adapter needs/,
    );
    expect(buildSettingsProblems({ ...local, VITE_AUTH: "magic" })).toContain(
      "VITE_AUTH must be firebase or oidc-mock",
    );
  });

  it("stop a real production build that asks for the mock adapter, before anything is built", () => {
    const web = fileURLToPath(new URL("..", import.meta.url));
    const result = spawnSync(
      process.execPath,
      [`${web}node_modules/vite/bin/vite.js`, "build", "--outDir", "/tmp/idleo-refused"],
      {
        cwd: web,
        env: {
          ...process.env,
          ...local,
          IDLEO_DEPLOYMENT: "production",
          VITE_API_URL: "https://api.example.invalid",
        },
        encoding: "utf8",
      },
    );
    expect(result.status).not.toBe(0);
    expect(result.stderr + result.stdout).toContain("Refusing to build the web app");
    expect(result.stderr + result.stdout).toContain("can't use the oidc-mock sign-in adapter");
  });
});
