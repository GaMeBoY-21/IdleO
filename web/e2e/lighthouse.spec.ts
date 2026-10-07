import { chromium, expect, test, type Page } from "@playwright/test";
import lighthouse from "lighthouse";
import desktopConfig from "lighthouse/core/config/desktop-config.js";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { signIn } from "./support";

/**
 * The performance and accessibility budgets (ADR 0018, web/README.md), on sign-in and the signed-in home. Lighthouse
 * runs in the same browser profile the test signed in with (sign-in lives in IndexedDB, so Lighthouse's own tab is
 * signed in too, with storage reset off). Mobile reports go to .lighthouseci/ for `lhci assert` against the
 * budgets in lighthouserc.json; desktop reports go to .lighthouseci-desktop/, for the record. Interaction to next
 * paint isn't measured by a page load, so it's measured here with web-vitals, during real interactions on a CPU
 * slowed four times.
 */
const PORT = 9333;
const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const user = process.env.E2E_LIGHTHOUSE_USER ?? `lighthouse-${Date.now()}`;

test.describe.configure({ mode: "serial" });

test("the budgets hold on sign-in and the signed-in home", async () => {
  const profile = mkdtempSync(join(tmpdir(), "idleo-lighthouse-"));
  const context = await chromium.launchPersistentContext(profile, {
    args: [`--remote-debugging-port=${PORT}`],
    viewport: { width: 412, height: 823 },
  });
  try {
    const page = context.pages()[0] ?? (await context.newPage());
    const summary: Record<string, Record<string, number>> = {};
    // Signed out first (Lighthouse resets storage for it), then signed in, with storage kept, for the home.
    for (const [name, path, signedIn] of [
      ["sign-in", "/sign-in/", false],
      ["home", "/", true],
    ] as const) {
      if (signedIn) {
        await signIn(page, user);
        if (new URL(page.url()).pathname === "/welcome/") {
          await page.getByRole("button", { name: "Go to your home" }).click();
        }
        await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
        // A cold cache, as a first visit has; only the sign-in, in IndexedDB, is kept.
        const cdp = await context.newCDPSession(page);
        await cdp.send("Network.clearBrowserCache");
        await cdp.detach();
      }
      for (const formFactor of ["mobile", "desktop"] as const) {
        const result = await lighthouse(
          `${WEB}${path}`,
          {
            port: PORT,
            output: "json",
            logLevel: "error",
            onlyCategories: ["performance", "accessibility", "best-practices"],
            disableStorageReset: signedIn,
          },
          formFactor === "desktop" ? desktopConfig : undefined,
        );
        if (!result) {
          throw new Error(`Lighthouse returned nothing for ${name}`);
        }
        // lhci reads .lighthouseci/lhr-<number>.json; the desktop reports are kept apart, for the record.
        const file =
          formFactor === "mobile"
            ? `.lighthouseci/lhr-${Date.now()}.json`
            : `.lighthouseci-desktop/${name}.json`;
        mkdirSync(file.slice(0, file.lastIndexOf("/")), { recursive: true });
        writeFileSync(file, result.report as string);
        const audits = result.lhr.audits;
        summary[`${name} (${formFactor})`] = {
          lcpMs: Math.round(audits["largest-contentful-paint"]?.numericValue ?? -1),
          tbtMs: Math.round(audits["total-blocking-time"]?.numericValue ?? -1),
          cls: audits["cumulative-layout-shift"]?.numericValue ?? -1,
          accessibility: Math.round((result.lhr.categories.accessibility?.score ?? 0) * 100),
          performance: Math.round((result.lhr.categories.performance?.score ?? 0) * 100),
        };
      }
    }
    summary["home (mobile)"]!.inpMs = await interactionToNextPaint(page);
    mkdirSync(".lighthouseci", { recursive: true });
    writeFileSync(".lighthouseci/summary.json", `${JSON.stringify(summary, null, 2)}\n`);
    console.table(summary);
    expect(summary["home (mobile)"]!.inpMs, "interaction to next paint, ms").toBeLessThanOrEqual(200);
  } finally {
    await context.close();
  }
});

/** The worst interaction latency across opening the menu, switching theme and moving between screens. */
async function interactionToNextPaint(page: Page): Promise<number> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await page.goto(`${WEB}/`);
  await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
  // Through DevTools, which the page's Content Security Policy rightly doesn't let a script tag do.
  await page.evaluate(readFileSync("node_modules/web-vitals/dist/web-vitals.iife.js", "utf8"));
  await page.evaluate(() => {
    const vitals = (
      window as unknown as {
        webVitals: { onINP: (callback: (metric: { value: number }) => void, options: object) => void };
      }
    ).webVitals;
    vitals.onINP((metric) => ((window as unknown as { __inp: number }).__inp = metric.value), {
      reportAllChanges: true,
    });
  });
  await page.getByRole("button", { name: "Your account and settings" }).click();
  await page.getByRole("menuitemradio", { name: "Dark" }).click();
  await page.getByRole("button", { name: "Your account and settings" }).click();
  await page.getByRole("menuitemradio", { name: "Match my device" }).click();
  await page.getByRole("link", { name: "Credit" }).first().click();
  await expect(page.getByRole("heading", { name: "Credit", level: 1 })).toBeVisible();
  await page.getByRole("link", { name: "Home" }).first().click();
  await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
  await page.waitForTimeout(500);
  const inp = await page.evaluate(() => (window as unknown as { __inp?: number }).__inp ?? 0);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
  return Math.round(inp);
}
