import { expect, test, type Page, type Request } from "@playwright/test";
import * as data from "./fixtures";
import * as publicData from "./fixtures-public";
import * as credit from "./fixtures-credit";
import * as hosting from "./fixtures-hosting";
import * as images from "./fixtures-images";
import * as privacy from "./fixtures-privacy";
import * as support from "./fixtures-support";
import * as trust from "./fixtures-trust";
import { hostingApi } from "./hosting-api";
import { HOST_URL } from "./sites";
import { fakeApi, recordLayoutShifts, signIn, type Answer } from "./support";

/**
 * No screen moves while it loads (web/DESIGN.md "Motion": cumulative layout shift 0). Each screen is opened
 * afresh with the API slowed down, so its skeleton paints first and the data replaces it; the font swap counts
 * too. Measured in every screens project: phone and laptop, light and dark.
 */
/**
 * Waits until this browser has kept the signed-in account (src/api/persisted-cache.ts saves at most once a second), as
 * it has for anyone who comes back: the screen then paints from it. Without the wait, a screen opened within that second
 * painted its skeleton instead and moved when the account came.
 */
async function waitForKeptAccount(page: Page): Promise<void> {
  const kept = () =>
    page.evaluate(
      () =>
        new Promise<string>((resolve) => {
          // idb-keyval's own database and store, made the same way if this is the first to open it.
          const opened = indexedDB.open("keyval-store");
          opened.onupgradeneeded = () => opened.result.createObjectStore("keyval");
          opened.onerror = () => resolve("");
          opened.onsuccess = () => {
            const read = opened.result.transaction("keyval").objectStore("keyval").get("idleo.home-cache");
            read.onsuccess = () => {
              opened.result.close();
              resolve(typeof read.result === "string" ? read.result : "");
            };
            read.onerror = () => {
              opened.result.close();
              resolve("");
            };
          };
        }),
    );
  await expect.poll(kept, { timeout: 5_000 }).toContain('"kept-me"');
}

const screens = [
  // The phone's "Book again" tile, and the laptop's "Run again" in the recent sessions: both come with the sessions.
  { name: "home", path: "/", ready: () => /Book again|Run again/ },
  { name: "credit", path: "/credit/", ready: () => "Show older" },
  { name: "a completed session", path: `/session/?id=${data.completed.id}`, ready: () => "result.json" },
  {
    name: "a running session",
    path: `/session/?id=${data.running.id}`,
    ready: () => "Last checkpoint saved",
  },
  {
    name: "a session finding its GPU",
    path: `/session/?id=${data.finding.id}`,
    ready: () => "Cancel request",
  },
  { name: "all sessions", path: "/sessions/?show=ended", ready: () => "Show more" },
  { name: "book", path: "/book/", ready: () => "Run your code" },
  { name: "book, a service", path: "/book/?service=fine-tune", ready: () => "≈ ₹3–8" },
  { name: "my files", path: "/files/", ready: () => "my-llm-7b/" },
  { name: "hosting", path: "/", site: HOST_URL, ready: () => "Add a machine" },
  {
    name: "a running machine",
    path: `/laptop/?id=${hosting.ROG.id}`,
    site: HOST_URL,
    ready: () => "Pause hosting",
  },
  {
    name: "a machine's price",
    path: `/laptop/price/?id=${hosting.ROG.id}`,
    site: HOST_URL,
    ready: () => "One session at a time",
  },
  {
    name: "what a machine ran",
    path: `/sessions/?id=${hosting.ROG.id}`,
    site: HOST_URL,
    ready: () => "Fine-tuning · 18 min",
  },
  { name: "earnings", path: "/earnings/", site: HOST_URL, ready: () => "Given back for lost time" },
  // The payouts' tiles arrive after the paint, each drawn first at its own height (ADR 0040).
  { name: "payouts", path: "/payouts/", site: HOST_URL, ready: () => "Didn't go through" },
  { name: "laptops", path: "/laptops/", site: HOST_URL, ready: () => "Add a laptop" },
  { name: "host help", path: "/help/", site: HOST_URL, ready: () => "The Windows app" },
  { name: "payout details", path: "/payout-details/", site: HOST_URL, ready: () => "Payouts go here" },
  { name: "account", path: "/account/", ready: () => "New device sign-in" },
  { name: "API keys", path: "/account/keys/", ready: () => "Dashboard" },
  { name: "reports", path: "/account/reports/", ready: () => "It ran at the speed of its GPU." },
  {
    // A check under way, a ready image with its warnings, a blocked one: each drawn at its own height from the first paint.
    name: "my images",
    path: "/account/images/",
    ready: () => "Downloading your image · 3.1 of 8.4 GB",
    routes: { "/v1/images": { json: images.imageList() } } as Record<string, Answer>,
  },
  {
    // The rating and the report arriving as a rated session with a report: the block's height is the same as unrated.
    name: "a completed session already rated and reported",
    path: `/session/?id=${data.unreportedSession.id}`,
    ready: () => "Report resolved, no refund",
    routes: {
      [`/v1/sessions/${data.unreportedSession.id}`]: { json: data.unreportedSession },
      "/v1/sessions/[^/]+/rating": { json: trust.ratingGiven(data.unreportedSession) },
      "/v1/disputes": { json: trust.disputesPage([trust.disputeClosed]) },
    } as Record<string, Answer>,
  },
  { name: "notifications", path: "/notifications/", ready: () => "Your credit is running low" },
  {
    name: "help",
    path: "/account/help/",
    ready: () => "#1042 · A privacy request",
    routes: {
      "/v1/public/contact": { json: support.contact() },
      "/v1/support/tickets": { json: support.tickets() },
      "/v1/hosts": { json: { hosts: [] } },
    } as Record<string, Answer>,
  },
  {
    name: "deleting the account",
    path: "/account/delete/",
    ready: () => "You give up",
    routes: { "/v1/me/deletion": { json: privacy.deletionNone() } } as Record<string, Answer>,
  },
  {
    name: "your data",
    path: "/account/data/",
    ready: () => "Kept until",
    routes: {
      "/v1/me/data": { json: privacy.yourData() },
      "/v1/me/exports": { json: privacy.exportReady() },
    } as Record<string, Answer>,
  },
  { name: "a CLI sign-in to approve", path: "/cli-login/?code=WDJB-MJHT", ready: () => "Approve" },
  { name: "adding a machine", path: "/add/", site: HOST_URL, ready: () => "Windows app: coming soon" },
  {
    name: "linking a laptop",
    path: `/link/${hosting.LINK_ID}/`,
    site: HOST_URL,
    ready: () => "Approve and start earning",
  },
];

test("sign-in loads without moving, its form arriving in the space kept for it", async ({ page }) => {
  // The email form's chunk held back, so its placeholder paints first and the form replaces it, as on a slow phone.
  await page.route(/\/assets\/EmailForm-[^/]+\.js$/, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 600));
    await route.continue();
  });
  const shifts = await recordLayoutShifts(page);
  await page.goto("/sign-in/");
  await expect(page.getByLabel("Email", { exact: true })).toBeVisible();
  await page.waitForTimeout(1000);
  const { total, sources } = await shifts();
  expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
});

for (const screen of screens) {
  test(`${screen.name} loads without moving`, async ({ page }) => {
    await page.clock.setFixedTime(data.NOW);
    const routes = {
      ...hostingApi(),
      "/v1/me": { json: data.me() },
      "/v1/me/notifications": { json: data.notificationChoices() },
      "/v1/api-keys": { json: data.apiKeys() },
      "/v1/notifications": { json: data.inbox() },
      "/v1/cli-logins": { json: data.pendingCliLogin() },
      "/v1/templates": { json: data.templates },
      "/v1/wallet": { json: data.asha.wallet },
      "/v1/availability": { json: data.availability },
      // The renter's home asks these once it has painted (ADR 0058).
      "/v1/usage": { json: data.usageOf(data.asha.sessions) },
      "/v1/public/availability": { json: publicData.availability() },
      "/v1/sessions": {
        json: data.sessionsPage(data.asha.sessions, {
          active: screen.path.includes("ended") ? false : undefined,
        }),
      },
      "/v1/sessions/[^/]+/events": "hold" as const,
      "/v1/sessions/[^/]+/logs": {
        json: { chunks: data.logChunks(data.running), nextCursor: null, complete: true },
      },
      "/v1/sessions/[^/]+/outputs": { json: data.outputs },
      "/v1/sessions/[^/]+/rating": { json: trust.ratingOpen(data.completed) },
      // A session's report arrives with its status in a row of the same height as "Report a problem".
      "/v1/disputes": { json: trust.disputesPage() },
      [`/v1/sessions/${data.running.id}`]: { json: data.running },
      [`/v1/sessions/${data.finding.id}`]: { json: data.finding },
      "/v1/uploads/[^/]+": { json: data.sessionUpload },
      "/v1/uploads": { json: data.uploadsPage() },
      "/v1/session-quotes": (_call: number, request: Request) =>
        data.quoteAnswer(request.postDataJSON() as { maxSpendPaise: number; vramNeededMb?: number }),
      "/v1/sessions/[^/]+": { json: data.completed },
      "/v1/wallet/transactions": { json: data.history(0) },
      // The Credit page's money in (ADR 0039), each arriving after the paint like the rest.
      "/v1/wallet/billing-details": { json: credit.billingDetails },
      "/v1/wallet/invoices": { json: credit.invoices },
      "/v1/wallet/refund-requests": { json: credit.noRefunds },
    };
    // The screen's own answers first: the first route that matches a path wins.
    const answers: Record<string, Answer> = { ...("routes" in screen ? screen.routes : {}) };
    for (const [path, answer] of Object.entries(routes)) {
      answers[path] ??= answer;
    }
    await fakeApi(page, answers);
    // The host site's screens on its own address, signed in there (ADR 0058).
    const site = "site" in screen ? screen.site : "";
    await signIn(page, "asha", "/", site);
    await waitForKeptAccount(page);
    await page.unrouteAll({ behavior: "wait" });
    await fakeApi(page, answers, { delayMs: 600 });
    const shifts = await recordLayoutShifts(page);
    await page.goto(`${site}${screen.path}`);
    await expect(page.getByText(screen.ready()).first()).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(1000);
    const { total, sources } = await shifts();
    expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
  });
}
