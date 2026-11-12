import { defineConfig, devices } from "@playwright/test";

/**
 * The web app's browser tests (ADR 0018), against the static build served by scripts/serve.ts and the local
 * stack's sign-in server (scripts/e2e/agent-online.sh). Three kinds:
 * - screens: every screen and state, with the API answered by fixtures, so each state is reproducible; checked
 *   with axe and compared with visual baselines at 390 and 1280 px, light and dark;
 * - journey: the real stack end to end, a booked session followed on the home screen to its charge;
 * - lighthouse: the performance and accessibility budgets, and a 10 MiB log on the session screen.
 *
 * Visual baselines aren't in git: CI keeps them as an artifact and compares each run with them; a change needs
 * an explicit update (web/README.md).
 */
const baseURL = process.env.WEB_URL ?? "http://localhost:3000";
const phone = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true };
// 1440 x 900: the size the laptop layout is judged at (web/DESIGN.md "Laptop layout") and the screenshots are taken at.
const laptop = { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 };

/**
 * The UI review (e2e/ui-review.ts): the sweep and every screens spec at five widths in both themes, only when
 * UI_REVIEW_DIR is set. 390 is a phone (its name says so, as `onPhone` reads it); 768 and up are not.
 */
const REVIEW_WIDTHS = (process.env.UI_REVIEW_WIDTHS ?? "1440,1280,1024,768,390").split(",").map(Number);
const REVIEW_THEMES = (process.env.UI_REVIEW_THEMES ?? "dark,light").split(",") as ("dark" | "light")[];
const reviewProjects = process.env.UI_REVIEW_DIR
  ? REVIEW_WIDTHS.flatMap((width) =>
      REVIEW_THEMES.map((theme) => ({
        name: width < 768 ? `ui-review-phone-${width}-${theme}` : `ui-review-${width}-${theme}`,
        testMatch:
          process.env.UI_REVIEW_SPECS === "0" ? /ui-review\.spec\.ts/ : /(ui-review|[/-]screens)\.spec\.ts$/,
        use:
          width < 768
            ? { ...phone, viewport: { width, height: 844 }, deviceScaleFactor: 1, colorScheme: theme }
            : { viewport: { width, height: 900 }, deviceScaleFactor: 1, colorScheme: theme },
      })),
    )
  : [];

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  // How many browsers run at once: the machine's half by default; PW_WORKERS=2 keeps a laptop cool on a long run.
  workers: process.env.PW_WORKERS ? Number(process.env.PW_WORKERS) : undefined,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  timeout: 60_000,
  snapshotPathTemplate: `${process.env.VISUAL_BASELINE_DIR ?? "visual-baseline"}/{projectName}/{arg}{ext}`,
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.002, animations: "disabled", caret: "hide" },
  },
  use: {
    baseURL,
    locale: "en-IN",
    timezoneId: "Asia/Kolkata",
    trace: "retain-on-failure",
    ...devices["Desktop Chrome"],
  },
  projects: [
    ...reviewProjects,
    {
      name: "screens-phone-light",
      testMatch: /(screens|admin-console|admin-actions|admin-first-load|layout-shift|firebase)\.spec\.ts/,
      use: { ...phone, colorScheme: "light" },
    },
    {
      name: "screens-phone-dark",
      testMatch: /(screens|admin-console|admin-actions|admin-first-load|layout-shift|firebase)\.spec\.ts/,
      use: { ...phone, colorScheme: "dark" },
    },
    {
      name: "screens-laptop-light",
      testMatch: /(screens|admin-console|admin-actions|admin-first-load|layout-shift|firebase)\.spec\.ts/,
      use: { ...laptop, colorScheme: "light" },
    },
    {
      name: "screens-laptop-dark",
      testMatch: /(screens|admin-console|admin-actions|admin-first-load|layout-shift|firebase)\.spec\.ts/,
      use: { ...laptop, colorScheme: "dark" },
    },
    { name: "console-budgets", testMatch: "console-budgets.spec.ts", use: { ...laptop }, timeout: 600_000 },
    // ./start.sh's development servers side by side (scripts/e2e/start-check.sh), never the built sites.
    { name: "dev-servers", testMatch: "dev-servers.spec.ts", use: { ...laptop }, timeout: 300_000 },
    { name: "journey", testMatch: "journey.spec.ts", use: { ...phone }, timeout: 240_000 },
    { name: "account-journey", testMatch: "account-journey.spec.ts", use: { ...phone }, timeout: 120_000 },
    { name: "privacy-journey", testMatch: "privacy-journey.spec.ts", use: { ...phone }, timeout: 180_000 },
    { name: "payments-journey", testMatch: "payments-journey.spec.ts", use: { ...phone }, timeout: 120_000 },
    { name: "payouts-journey", testMatch: "payouts-journey.spec.ts", use: { ...phone }, timeout: 120_000 },
    { name: "hosting-journey", testMatch: "hosting-journey.spec.ts", use: { ...laptop }, timeout: 480_000 },
    {
      name: "admin-journey",
      testMatch: /admin-(journey|access|queues)\.spec\.ts/,
      use: { ...laptop },
      timeout: 480_000,
    },
    {
      name: "admin-roles",
      testMatch: "admin-roles.spec.ts",
      use: { ...laptop },
      workers: 1,
      timeout: 600_000,
    },
    {
      name: "recording",
      testMatch: /recording(-laptop)?\.spec\.ts/,
      use: {
        ...phone,
        deviceScaleFactor: 1,
        colorScheme: "light",
        video: { mode: "on", size: phone.viewport },
      },
    },
    {
      name: "lighthouse",
      testMatch: /(lighthouse|performance|admin-frames)\.spec\.ts/,
      workers: 1,
      timeout: 2_400_000,
    },
  ],
});
