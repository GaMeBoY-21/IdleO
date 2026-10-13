// @vitest-environment node
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { buildSettingsProblems } from "./build-settings";

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
  VITE_AUTH: "firebase",
  VITE_FIREBASE_API_KEY: "placeholder",
  VITE_FIREBASE_AUTH_DOMAIN: "placeholder.firebaseapp.com",
  VITE_FIREBASE_PROJECT_ID: "placeholder",
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
