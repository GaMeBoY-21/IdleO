import { chromium, expect, test, type APIRequestContext, type CDPSession, type Page } from "@playwright/test";
import lighthouse from "lighthouse";
import desktopConfig from "lighthouse/core/config/desktop-config.js";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readBaseline, regressions, REGRESSION_LIMIT } from "../scripts/lighthouse-baseline";
import { API, signIn } from "./support";

/**
 * The performance and accessibility budgets (ADR 0018, web/README.md), on sign-in, Home, Book, the session screen, the Hosting tab, a machine and Account. Lighthouse
 * runs in the same browser profile the test signed in with (sign-in lives in IndexedDB, so Lighthouse's own tab is
 * signed in too, with storage reset off). Each screen is measured three times on the mobile profile, each from a cold
 * cache, and `lhci assert` holds the median of the three to the budgets in lighthouserc.json: one run alone carries
 * the machine's noise. Those reports go to .lighthouseci/; desktop's, one run each, to .lighthouseci-desktop/, for the
 * record; and every run's HTML report to .lighthouseci/reports/, which CI keeps. Interaction to next paint isn't
 * measured by a page load, so it's measured here with web-vitals, during real interactions on a CPU slowed four
 * times: on Home (the menu, the theme, moving between screens), typing in the booking form, and the session screen
 * while its log streams in.
 */
const PORT = 9333;
const WEB = process.env.WEB_URL ?? "http://localhost:3000";
const user = process.env.E2E_LIGHTHOUSE_USER ?? `lighthouse-${Date.now()}`;
const signInServer = process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local";
/**
 * How the page load is throttled. `simulate` is Lighthouse's default: it loads the page unthrottled and works out what a
 * slow phone would have seen. `devtools` loads it through Chrome's own network and CPU throttling, so the numbers follow
 * what the browser really painted. LIGHTHOUSE_THROTTLING picks one; see "Lighthouse measures what users see" in web/README.md.
 */
const THROTTLING = process.env.LIGHTHOUSE_THROTTLING === "devtools" ? "devtools" : "simulate";
/**
 * Measuring, not asserting: LIGHTHOUSE_PAGES names the screens (home,hosting,add,link,book,session) and LIGHTHOUSE_RUNS how
 * many runs each gets. Only the mobile profile runs, and the interaction checks are left out; every run's numbers go to
 * .lighthouseci/measure-<throttling>.json.
 */
const ONLY = process.env.LIGHTHOUSE_PAGES?.split(",").filter(Boolean) ?? null;
/**
 * Chrome's own throttling, beside the simulated method the budgets are held to, on each asserted mobile screen: the median of
 * DEVTOOLS_RUNS (three: a first run on a cold browser is often 0.5 s slower). Report-only: it isn't the budget (2.0 s, simulated;
 * real throttling puts every screen at about 2.15 s), but it must stay under this ceiling, so a drift shows before it is a problem.
 */
const DEVTOOLS_CEILING_MS = 2_500;
/**
 * The ceilings above are for the machine they were set on. CI's hosted runner is slower (the same screens read 2.45 to 2.65 s there
 * against about 2.15 s), so CI sets LIGHTHOUSE_BASELINE to the file the last green run on main left, and then both report-only
 * figures are held to that instead: reported against the ceiling, never failed by it, and failed when a screen is more than
 * REGRESSION_LIMIT slower than that run's median. The simulated budget in lighthouserc.json is the same everywhere. Every run
 * writes its own medians to .lighthouseci-devtools/baseline.json, which CI keeps when it is main's green run (ADR 0018).
 */
const BASELINE_FILE = process.env.LIGHTHOUSE_BASELINE ?? null;
const DEVTOOLS_RUNS = Number(process.env.LIGHTHOUSE_DEVTOOLS_RUNS ?? 3);
/**
 * Credit with no figures kept from an earlier visit, beside the asserted runs, which have them like every other screen:
 * its largest paint is the available credit, which then waits for the wallet, and the wallet can't be asked for
 * before the app's own script has run, since only the sign-in adapter holds the token (ADR 0039, "The Credit page's
 * speed"). Report-only, the median of FIRST_VISIT_RUNS, but it must stay under this ceiling.
 */
const FIRST_VISIT_CEILING_MS = 2_500;
const FIRST_VISIT_RUNS = Number(process.env.LIGHTHOUSE_FIRST_VISIT_RUNS ?? 3);
/** Runs per screen on the mobile profile; the median is asserted. */
const RUNS = Number(process.env.LIGHTHOUSE_RUNS ?? 3);

type Measured = { lcpMs: number; tbtMs: number; cls: number; accessibility: number; performance: number };
const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) / 2)]!;

test.describe.configure({ mode: "serial" });

test("the budgets hold on sign-in, Home, Book and the session screen", async () => {
  const profile = mkdtempSync(join(tmpdir(), "idleo-lighthouse-"));
  const context = await chromium.launchPersistentContext(profile, {
    args: [`--remote-debugging-port=${PORT}`],
    viewport: { width: 412, height: 823 },
  });
  try {
    const page = context.pages()[0] ?? (await context.newPage());
    const summary: Record<
      string,
      Measured & {
        lcpRunsMs: number[];
        inpMs?: number;
        devtoolsLcpMs?: number;
        devtoolsRunsMs?: number[];
        firstVisitLcpMs?: number;
        firstVisitRunsMs?: number[];
      }
    > = {};
    // Signed out first (Lighthouse resets storage for it), then signed in, with storage kept, for the rest. The
    // session screen shows a long run this user booked (the script gave it credit), live through all three runs;
    // it's stopped once measured, so the one host is free for the session opened by a link.
    let sessionId = "";
    let liveId = "";
    let logsInpMs = 0;
    let machineId = "";
    let linkId = "";
    for (const [name, signedIn] of [
      ["sign-in", false],
      ["home", true],
      ["book", true],
      ["session", true],
      // For the record, not a budget: a session the app has never listed, opened by a link (ADR 0020).
      ["session-link", true],
      // An owner's two screens (ADR 0023): the Hosting tab, and one of its machines.
      ["hosting", true],
      ["machine", true],
      // The account, with its notification choices (ADR 0024).
      ["account", true],
      // Its keys, which the command line and scripts act through (ADR 0025).
      ["keys", true],
      // What the sessions and machines have told the person (ADR 0026).
      ["notifications", true],
      // Credit, where renters pay (ADR 0039): a page load of its own, which loads Razorpay's Checkout only on Pay.
      ["credit", true],
      // A laptop joining (ADR 0032): the page that explains it, and the page where its owner approves its link.
      ["add", true],
      ["link", true],
    ] as const) {
      if (name === "home") {
        await signIn(page, user);
        if (new URL(page.url()).pathname === "/welcome/") {
          await page.getByRole("button", { name: "Go to your home" }).click();
        }
        await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeAttached();
      }
      if (name === "book") {
        // Booked after Home is measured, so Home's kept figures were its latest; then Home is opened, as a renter
        // would, so its kept list holds the session the session screen is measured on.
        liveId = await bookOne(context.request, "home", "long-run");
        sessionId = liveId;
        const listed = page.waitForResponse(
          async (response) =>
            new URL(response.url()).pathname === "/v1/sessions" &&
            (await response.text()).includes(sessionId),
          { timeout: 30_000 },
        );
        await page.goto(`${WEB}/`);
        await listed;
        await page.waitForTimeout(1_500);
      }
      if (name === "session-link") {
        // The session screen's interaction to next paint, while its log is still streaming in; then the run stops.
        logsInpMs = await logsInp(page, liveId);
        await stopOne(context.request, liveId);
        sessionId = await bookOne(context.request, "link", "cuda-smoke-test");
      }
      if (name === "hosting") {
        machineId = await addMachine(context.request);
      }
      if (name === "link") {
        linkId = await startLink(context.request);
      }
      const path = {
        "sign-in": "/sign-in/",
        home: "/",
        book: "/book/?service=fine-tune",
        session: `/session/?id=${sessionId}`,
        "session-link": `/session/?id=${sessionId}`,
        hosting: "/hosting/",
        machine: `/hosting/machine/?id=${machineId}`,
        account: "/account/",
        keys: "/account/keys/",
        notifications: "/notifications/",
        credit: "/credit/",
        add: "/hosting/add/",
        link: `/link/${linkId}/`,
      }[name];
      if (ONLY && !ONLY.includes(name)) {
        continue;
      }
      for (const formFactor of ONLY ? (["mobile"] as const) : (["mobile", "desktop"] as const)) {
        // lhci reads .lighthouseci/lhr-<number>.json; the desktop reports are kept apart, for the record.
        const asserted = formFactor === "mobile" && name !== "session-link";
        const runs: Measured[] = [];
        const coldStart = async (forgetFigures = false) => {
          if (signedIn) {
            // This tab only signs in and books; left open on a screen it keeps polling (the bell asks for notifications
            // every 30 s), and each poll saves its own few figures over the ones the measured page kept for the next
            // run (the Hosting tab's machines), so a later run began cold.
            if (forgetFigures) {
              // The figures kept from earlier visits are idb-keyval's (src/api/persisted-cache.ts), deleted from a page
              // of the app's own origin that asks for none (its 404).
              await page.goto(`${WEB}/no-such-page/`);
              await page.evaluate(
                () =>
                  new Promise<void>((resolve, reject) => {
                    const opened = indexedDB.open("keyval-store");
                    opened.onerror = () => reject(new Error(String(opened.error)));
                    opened.onsuccess = () => {
                      const tx = opened.result.transaction("keyval", "readwrite");
                      tx.objectStore("keyval").delete("idleo.home-cache");
                      tx.oncomplete = () => resolve();
                      tx.onerror = () => reject(new Error(String(tx.error)));
                    };
                  }),
              );
            }
            await page.goto("about:blank");
            // A cold cache, as a first visit has; only the sign-in, in IndexedDB, is kept.
            const cdp = await context.newCDPSession(page);
            await cdp.send("Network.clearBrowserCache");
            await cdp.detach();
          }
        };
        const firstVisit: number[] = [];
        if (name === "credit" && asserted && !ONLY && THROTTLING === "simulate") {
          // Before the asserted runs, so the last of these keeps the figures again for them and the screens after.
          // Not in .lighthouseci/: lhci asserts every report there against the budget.
          for (let run = 1; run <= FIRST_VISIT_RUNS; run++) {
            await coldStart(true);
            const first = await lighthouse(`${WEB}${path}`, {
              port: PORT,
              output: "json",
              logLevel: "error",
              onlyCategories: ["performance"],
              disableStorageReset: signedIn,
              throttlingMethod: THROTTLING,
            });
            if (!first) {
              throw new Error(`Lighthouse returned nothing for ${name} with no kept figures`);
            }
            mkdirSync(".lighthouseci-first-visit", { recursive: true });
            writeFileSync(`.lighthouseci-first-visit/${name}-${run}.json`, first.report as string);
            firstVisit.push(Math.round(first.lhr.audits["largest-contentful-paint"]?.numericValue ?? -1));
          }
        }
        for (let run = 1; run <= (asserted ? RUNS : 1); run++) {
          await coldStart();
          const result = await lighthouse(
            `${WEB}${path}`,
            {
              port: PORT,
              output: ["json", "html"],
              logLevel: "error",
              onlyCategories: ["performance", "accessibility", "best-practices"],
              disableStorageReset: signedIn,
              throttlingMethod: THROTTLING,
            },
            formFactor === "desktop" ? desktopConfig : undefined,
          );
          if (!result) {
            throw new Error(`Lighthouse returned nothing for ${name}`);
          }
          const [json, html] = result.report as string[];
          const file = asserted
            ? `.lighthouseci/lhr-${Date.now()}.json`
            : `.lighthouseci-desktop/${name}-${formFactor}.json`;
          mkdirSync(file.slice(0, file.lastIndexOf("/")), { recursive: true });
          writeFileSync(file, json!);
          mkdirSync(".lighthouseci/reports", { recursive: true });
          writeFileSync(`.lighthouseci/reports/${name}-${formFactor}-${run}.html`, html!);
          const audits = result.lhr.audits;
          runs.push({
            lcpMs: Math.round(audits["largest-contentful-paint"]?.numericValue ?? -1),
            tbtMs: Math.round(audits["total-blocking-time"]?.numericValue ?? -1),
            cls: audits["cumulative-layout-shift"]?.numericValue ?? -1,
            accessibility: Math.round((result.lhr.categories.accessibility?.score ?? 0) * 100),
            performance: Math.round((result.lhr.categories.performance?.score ?? 0) * 100),
          });
        }
        // The median of each figure, as lhci asserts it, and every run's LCP beside it.
        summary[`${name} (${formFactor})`] = {
          lcpMs: median(runs.map((r) => r.lcpMs)),
          tbtMs: median(runs.map((r) => r.tbtMs)),
          cls: median(runs.map((r) => r.cls)),
          accessibility: median(runs.map((r) => r.accessibility)),
          performance: median(runs.map((r) => r.performance)),
          lcpRunsMs: runs.map((r) => r.lcpMs),
          ...(firstVisit.length > 0
            ? { firstVisitLcpMs: median(firstVisit), firstVisitRunsMs: firstVisit }
            : {}),
        };
        if (asserted && !ONLY && THROTTLING === "simulate" && DEVTOOLS_RUNS > 0) {
          // Not in .lighthouseci/: lhci asserts every report there against the simulated budget.
          const devtools: number[] = [];
          for (let run = 1; run <= DEVTOOLS_RUNS; run++) {
            await coldStart();
            const real = await lighthouse(`${WEB}${path}`, {
              port: PORT,
              output: "json",
              logLevel: "error",
              onlyCategories: ["performance"],
              disableStorageReset: signedIn,
              throttlingMethod: "devtools",
            });
            if (!real) {
              throw new Error(`Lighthouse returned nothing for ${name} with Chrome's throttling`);
            }
            mkdirSync(".lighthouseci-devtools", { recursive: true });
            writeFileSync(`.lighthouseci-devtools/${name}-${run}.json`, real.report as string);
            devtools.push(Math.round(real.lhr.audits["largest-contentful-paint"]?.numericValue ?? -1));
          }
          summary[`${name} (${formFactor})`]!.devtoolsLcpMs = median(devtools);
          summary[`${name} (${formFactor})`]!.devtoolsRunsMs = devtools;
        }
      }
    }
    mkdirSync(".lighthouseci", { recursive: true });
    if (ONLY) {
      writeFileSync(`.lighthouseci/measure-${THROTTLING}.json`, `${JSON.stringify(summary, null, 2)}\n`);
      console.table(summary);
      return;
    }
    summary["home (mobile)"]!.inpMs = await interactionToNextPaint(page);
    summary["book (mobile)"]!.inpMs = await typingInp(page);
    summary["session (mobile)"]!.inpMs = logsInpMs;
    summary["machine (mobile)"]!.inpMs = await hostingInp(page);
    summary["credit (mobile)"]!.inpMs = await payInp(page);
    writeFileSync(".lighthouseci/summary.json", `${JSON.stringify(summary, null, 2)}\n`);
    console.table(summary);
    const devtools: Record<string, number> = {};
    const firstVisit: Record<string, number> = {};
    for (const [name, found] of Object.entries(summary)) {
      if (found.devtoolsLcpMs !== undefined) {
        devtools[name] = found.devtoolsLcpMs;
      }
      if (found.firstVisitLcpMs !== undefined) {
        firstVisit[name] = found.firstVisitLcpMs;
      }
    }
    mkdirSync(".lighthouseci-devtools", { recursive: true });
    writeFileSync(
      ".lighthouseci-devtools/baseline.json",
      `${JSON.stringify({ devtools, firstVisit }, null, 2)}\n`,
    );
    // Soft: every check below still runs when one fails, so the run reports them all.
    const baseline = BASELINE_FILE ? readBaseline(BASELINE_FILE) : null;
    if (BASELINE_FILE && !baseline) {
      console.log(`no baseline at ${BASELINE_FILE}: this run's medians become the next one's`);
    }
    for (const [what, measured, ceiling, before, runsOf] of [
      [
        "with Chrome's own throttling",
        devtools,
        DEVTOOLS_CEILING_MS,
        baseline?.devtools,
        (name: string) => summary[name]?.devtoolsRunsMs,
      ],
      [
        "with no figures kept from an earlier visit",
        firstVisit,
        FIRST_VISIT_CEILING_MS,
        baseline?.firstVisit,
        (name: string) => summary[name]?.firstVisitRunsMs,
      ],
    ] as const) {
      for (const [name, ms] of Object.entries(measured)) {
        const message = `${name}: largest contentful paint ${what}, ${ms} ms against a ceiling of ${ceiling} ms`;
        if (BASELINE_FILE) {
          if (ms > ceiling) {
            console.warn(`report-only: ${message}`);
          }
        } else {
          expect
            .soft(ms, `${message} (report-only), runs ${runsOf(name)?.join(" ")}`)
            .toBeLessThanOrEqual(ceiling);
        }
      }
      if (before) {
        expect
          .soft(
            regressions(measured, before),
            `largest contentful paint ${what}: more than ${REGRESSION_LIMIT * 100}% slower than the last green run on main`,
          )
          .toEqual([]);
      }
    }
    for (const name of ["home", "book", "session", "machine", "credit"]) {
      expect
        .soft(summary[`${name} (mobile)`]!.inpMs, `${name}: interaction to next paint, ms`)
        .toBeLessThanOrEqual(200);
    }
  } finally {
    await context.close();
  }
});

async function renterToken(request: APIRequestContext): Promise<string> {
  const tokenResponse = await request.post(`${signInServer}/token`, {
    form: {
      grant_type: "client_credentials",
      client_id: user,
      client_secret: "unused",
      scope: "openid",
      auth_time: String(Math.floor(Date.now() / 1000)),
    },
  });
  return ((await tokenResponse.json()) as { access_token: string }).access_token;
}

/** A machine for the Hosting tab to list, added through the API as the web app's first step does. */
async function addMachine(request: APIRequestContext): Promise<string> {
  const added = await request.post(`${API}/v1/hosts`, {
    headers: {
      Authorization: `Bearer ${await renterToken(request)}`,
      "Idempotency-Key": `${user}-lighthouse-machine`,
    },
    data: { name: "Lighthouse laptop" },
  });
  expect(added.ok(), `adding a machine for the Hosting tab: ${added.status()}`).toBe(true);
  return ((await added.json()) as { id: string }).id;
}

/** A laptop asking to be linked, as the agent does it: no sign-in, and the page that approves it is the person's. */
async function startLink(request: APIRequestContext): Promise<string> {
  const started = await request.post(`${API}/v1/host-links`, {
    data: {
      machineName: "Lighthouse laptop",
      os: "windows",
      agentVersion: "0.1.0",
      gpus: [{ model: "NVIDIA GeForce RTX 4060 Laptop GPU", vramMb: 8188 }],
    },
  });
  expect(started.ok(), `asking for a link: ${started.status()}`).toBe(true);
  return ((await started.json()) as { id: string }).id;
}

/** The owner's screens: opening a machine from the Hosting tab, and choosing how long to pause it. */
async function hostingInp(page: Page): Promise<number> {
  const cdp = await measured(page, "/hosting/");
  await page.getByRole("link", { name: /Lighthouse laptop/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Lighthouse laptop" })).toBeVisible();
  await page.getByRole("button", { name: "Pause hosting" }).click();
  await page.getByLabel("1 hour").check();
  await page.getByLabel("Until I resume").check();
  await page.getByRole("button", { name: "Keep hosting" }).click();
  await page.getByRole("button", { name: /Rename or remove/ }).click();
  return finish(page, cdp);
}

/**
 * Paying on Credit (ADR 0039): its billing details saved, a pack chosen, Pay pressed, which loads Checkout's script then
 * (the fake Razorpay's on this stack), its window opened and closed. The page itself is measured like every other;
 * this is what pressing Pay costs.
 */
async function payInp(page: Page): Promise<number> {
  const cdp = await measured(page, "/credit/");
  await page.getByLabel("Your state").selectOption("29");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved.")).toBeVisible();
  await page.getByRole("button", { name: "₹1,000" }).click();
  await page.getByRole("button", { name: "Pay ₹1,000.00" }).click();
  const checkout = page.locator("[data-fake-checkout]");
  await expect(checkout).toBeVisible({ timeout: 15_000 });
  await checkout.getByRole("button", { name: "Close" }).click();
  await expect(page.getByText(/Paid through Razorpay/)).toBeVisible();
  return finish(page, cdp);
}

/** A session for the session screen, booked through the API as the CLI would. */
async function bookOne(request: APIRequestContext, key: string, templateId: string): Promise<string> {
  const booked = await request.post(`${API}/v1/sessions`, {
    headers: {
      Authorization: `Bearer ${await renterToken(request)}`,
      "Idempotency-Key": `${user}-lighthouse-${key}`,
    },
    data: { templateId, maxPricePaisePerMin: 5, maxSpendPaise: 10_000 },
  });
  expect(booked.ok(), `booking for the session screen: ${booked.status()}`).toBe(true);
  return ((await booked.json()) as { id: string }).id;
}

/** Stops a session, as its renter would; the host is free again once its last checkpoint is written. */
async function stopOne(request: APIRequestContext, id: string): Promise<void> {
  const stopped = await request.post(`${API}/v1/sessions/${id}/stop`, {
    headers: { Authorization: `Bearer ${await renterToken(request)}` },
    data: {},
  });
  expect(stopped.ok(), `stopping the long run: ${stopped.status()}`).toBe(true);
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

/**
 * Answering a service's questions as a renter does: a preset, a budget of their own, and Advanced's fields typed in
 * (the learning rate, the most per hour), on the fine-tune's screen.
 */
async function typingInp(page: Page): Promise<number> {
  const cdp = await measured(page, "/book/?service=fine-tune");
  await page.getByRole("button", { name: /^Quick/ }).click();
  await page.getByRole("button", { name: "Other amount" }).click();
  await page.getByRole("button", { name: /^Advanced/ }).click();
  for (const [label, text] of [
    ["Stop at, in ₹", "250"],
    ["Learning rate (optional)", "0.0003"],
    ["The most per hour, in ₹ (optional)", "7.20"],
  ] as const) {
    const field = page.getByLabel(label, { exact: true });
    await field.fill("");
    await field.pressSequentially(text, { delay: 50 });
  }
  await page.getByRole("button", { name: /^Thorough/ }).click();
  return finish(page, cdp);
}

/** The session screen while its log streams in: scrolling the panel with the keyboard and using its row. */
async function logsInp(page: Page, sessionId: string): Promise<number> {
  const cdp = await measured(page, `/session/?id=${sessionId}`);
  const panel = page.getByTestId("log-panel");
  const toggle = page.getByRole("button", { name: /^Logs/ });
  // A phone's width: the logs start collapsed, under their row.
  await toggle.click({ timeout: 30_000 });
  await expect(panel).toBeVisible({ timeout: 30_000 });
  for (let round = 0; round < 3; round++) {
    await panel.focus();
    for (const key of ["PageUp", "ArrowUp", "PageDown", "End"]) {
      await page.keyboard.press(key);
    }
    await toggle.click();
    await toggle.click();
    await page.waitForTimeout(1_000);
  }
  return finish(page, cdp);
}

/** The worst interaction latency across opening the More menu and moving between screens. */
async function interactionToNextPaint(page: Page): Promise<number> {
  const cdp = await measured(page, "/");
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeAttached();
  await page.getByRole("button", { name: "More" }).click();
  await expect(page.getByRole("menuitem", { name: "My files" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menuitem", { name: "Sign out" })).toHaveCount(0);
  // Not Credit: it's a page load of its own (its own security policy, ADR 0039), which payInp measures.
  await page.getByRole("link", { name: "Hosting" }).first().click();
  await expect(page.getByRole("heading", { name: "Hosting", level: 1 })).toBeVisible();
  await page.getByRole("link", { name: "Home" }).first().click();
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeAttached();
  return finish(page, cdp);
}
