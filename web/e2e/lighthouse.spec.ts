import { chromium, expect, test, type APIRequestContext, type CDPSession, type Page } from "@playwright/test";
import lighthouse from "lighthouse";
import desktopConfig from "lighthouse/core/config/desktop-config.js";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { API, signIn } from "./support";

/**
 * The performance and accessibility budgets (ADR 0018, web/README.md), on sign-in, Home, Book and the session screen. Lighthouse
 * runs in the same browser profile the test signed in with (sign-in lives in IndexedDB, so Lighthouse's own tab is
 * signed in too, with storage reset off). Mobile reports go to .lighthouseci/ for `lhci assert` against the
 * budgets in lighthouserc.json; desktop reports go to .lighthouseci-desktop/, for the record. Interaction to next
 * paint isn't measured by a page load, so it's measured here with web-vitals, during real interactions on a CPU
 * slowed four times: on Home (the menu, the theme, moving between screens), typing in the booking form, and the
 * session screen while its log streams in.
 */
const PORT = 9333;
const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const user = process.env.E2E_LIGHTHOUSE_USER ?? `lighthouse-${Date.now()}`;
const signInServer = process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local";

test.describe.configure({ mode: "serial" });

test("the budgets hold on sign-in, Home, Book and the session screen", async () => {
  const profile = mkdtempSync(join(tmpdir(), "idleo-lighthouse-"));
  const context = await chromium.launchPersistentContext(profile, {
    args: [`--remote-debugging-port=${PORT}`],
    viewport: { width: 412, height: 823 },
  });
  try {
    const page = context.pages()[0] ?? (await context.newPage());
    const summary: Record<string, Record<string, number>> = {};
    // Signed out first (Lighthouse resets storage for it), then signed in, with storage kept, for the rest. The
    // session screen shows a session this user booked (the script gave it credit), live.
    let sessionId = "";
    for (const [name, signedIn] of [
      ["sign-in", false],
      ["home", true],
      ["book", true],
      ["session", true],
      // For the record, not a budget: a session the app has never listed, opened by a link (ADR 0020).
      ["session-link", true],
    ] as const) {
      if (name === "home") {
        await signIn(page, user);
        if (new URL(page.url()).pathname === "/welcome/") {
          await page.getByRole("button", { name: "Go to your home" }).click();
        }
        await expect(page.getByRole("heading", { name: "Home" })).toBeAttached();
      }
      if (name === "book") {
        // Booked after Home is measured, so Home's kept figures were its latest; then Home is opened, as a renter
        // would, so its kept list holds the session the session screen is measured on.
        sessionId = await bookOne(context.request, "home");
        await page.goto(`${WEB}/`);
        await expect(page.getByRole("link", { name: /CUDA smoke test/ }).first()).toBeVisible({
          timeout: 30_000,
        });
        await page.waitForTimeout(1_500);
      }
      if (name === "session-link") {
        sessionId = await bookOne(context.request, "link");
      }
      const path = {
        "sign-in": "/sign-in/",
        home: "/",
        book: "/book/",
        session: `/session/?id=${sessionId}`,
        "session-link": `/session/?id=${sessionId}`,
      }[name];
      if (signedIn) {
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
          formFactor === "mobile" && name !== "session-link"
            ? `.lighthouseci/lhr-${Date.now()}.json`
            : `.lighthouseci-desktop/${name}-${formFactor}.json`;
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
    summary["book (mobile)"]!.inpMs = await typingInp(page);
    summary["session (mobile)"]!.inpMs = await logsInp(page, sessionId);
    mkdirSync(".lighthouseci", { recursive: true });
    writeFileSync(".lighthouseci/summary.json", `${JSON.stringify(summary, null, 2)}\n`);
    console.table(summary);
    for (const name of ["home", "book", "session"]) {
      expect(
        summary[`${name} (mobile)`]!.inpMs,
        `${name}: interaction to next paint, ms`,
      ).toBeLessThanOrEqual(200);
    }
  } finally {
    await context.close();
  }
});

/** A cheap session for the session screen: the smoke test, booked through the API as the CLI would. */
async function bookOne(request: APIRequestContext, key = "home"): Promise<string> {
  const tokenResponse = await request.post(`${signInServer}/token`, {
    form: { grant_type: "client_credentials", client_id: user, client_secret: "unused", scope: "openid" },
  });
  const { access_token: token } = (await tokenResponse.json()) as { access_token: string };
  const booked = await request.post(`${API}/v1/sessions`, {
    headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": `${user}-lighthouse-${key}` },
    data: { templateId: "cuda-smoke-test", maxPricePaisePerMin: 5, maxSpendPaise: 10_000 },
  });
  expect(booked.ok(), `booking for the session screen: ${booked.status()}`).toBe(true);
  return ((await booked.json()) as { id: string }).id;
}

/** Opens a page on a CPU slowed four times, with web-vitals recording interaction to next paint. */
async function measured(page: Page, path: string): Promise<CDPSession> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await page.goto(`${WEB}${path}`);
  await page.waitForLoadState("load");
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
  return cdp;
}

async function finish(page: Page, cdp: CDPSession): Promise<number> {
  await page.waitForTimeout(500);
  const inp = await page.evaluate(() => (window as unknown as { __inp?: number }).__inp ?? 0);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
  await cdp.detach();
  return Math.round(inp);
}

/** Typing in the booking form: the greeting, a number, the price and the cap, as a renter fills them in. */
async function typingInp(page: Page): Promise<number> {
  const cdp = await measured(page, "/book/?template=echo-input&step=configure");
  await page
    .getByLabel("Greeting", { exact: true })
    .pressSequentially("hello from lighthouse", { delay: 50 });
  await page.getByLabel("Repeat (optional)").fill("");
  await page.getByLabel("Repeat (optional)").pressSequentially("3", { delay: 50 });
  await page.getByLabel("The most per hour, in ₹").fill("");
  await page.getByLabel("The most per hour, in ₹").pressSequentially("7.20", { delay: 50 });
  await page.getByLabel("Spending cap, in ₹").fill("");
  await page.getByLabel("Spending cap, in ₹").pressSequentially("250", { delay: 50 });
  return finish(page, cdp);
}

/** The session screen while its log streams in: scrolling the panel with the keyboard and using its buttons. */
async function logsInp(page: Page, sessionId: string): Promise<number> {
  const cdp = await measured(page, `/session/?id=${sessionId}`);
  const panel = page.getByTestId("log-panel");
  const toggle = page.getByRole("button", { name: /Show logs/ });
  // A phone's width: the logs start collapsed.
  await toggle.click({ timeout: 30_000 });
  await expect(panel).toBeVisible({ timeout: 30_000 });
  for (let round = 0; round < 3; round++) {
    await panel.focus();
    for (const key of ["PageUp", "ArrowUp", "PageDown", "End"]) {
      await page.keyboard.press(key);
    }
    await page.getByRole("button", { name: "Hide logs" }).click();
    await page.getByRole("button", { name: /Show logs/ }).click();
    await page.waitForTimeout(1_000);
  }
  return finish(page, cdp);
}

/** The worst interaction latency across opening the menu, switching theme and moving between screens. */
async function interactionToNextPaint(page: Page): Promise<number> {
  const cdp = await measured(page, "/");
  await expect(page.getByRole("heading", { name: "Home" })).toBeAttached();
  await page.getByRole("button", { name: "Your account and settings" }).click();
  await page.getByRole("menuitemradio", { name: "Dark" }).click();
  await page.getByRole("button", { name: "Your account and settings" }).click();
  await page.getByRole("menuitemradio", { name: "Match my device" }).click();
  await page.getByRole("link", { name: "Credit" }).first().click();
  await expect(page.getByRole("heading", { name: "Credit", level: 1 })).toBeVisible();
  await page.getByRole("link", { name: "Home" }).first().click();
  await expect(page.getByRole("heading", { name: "Home" })).toBeAttached();
  return finish(page, cdp);
}
