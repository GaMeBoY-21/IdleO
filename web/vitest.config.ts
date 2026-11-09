import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const src = (path = "") => fileURLToPath(new URL(path ? `./src/${path}` : "./src", import.meta.url));

/**
 * `@site` is the site a build is for (vite.config.ts, ADR 0058). The tests run as the renter app's, as its build does;
 * the host project runs again the tests that draw a whole site (its pre-rendered frames, its bars) with the host site's.
 */
const SITE_TESTS = ["src/test/prerender.test.tsx", "src/test/site-frame.test.tsx"];

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    env: { VITE_AUTH: "oidc-mock" },
    testTimeout: 30_000,
    css: false,
    projects: [
      {
        extends: true,
        resolve: { alias: { "@site": src("sites/app"), "@": src() } },
        test: { name: "app", include: ["src/**/*.test.{ts,tsx}", "scripts/**/*.test.ts"] },
      },
      {
        extends: true,
        resolve: { alias: { "@site": src("sites/host"), "@": src() } },
        test: { name: "host", include: SITE_TESTS, env: { IDLEO_WEB_SITE: "host" } },
      },
    ],
  },
});
