import { expect, test, type Request } from "@playwright/test";
import * as data from "./fixtures";
import * as credit from "./fixtures-credit";
import * as hosting from "./fixtures-hosting";
import * as images from "./fixtures-images";
import * as privacy from "./fixtures-privacy";
import * as support from "./fixtures-support";
import * as trust from "./fixtures-trust";
import { hostingApi } from "./hosting-api";
import { fakeApi, recordLayoutShifts, signIn, type Answer } from "./support";

/**
 * No screen moves while it loads (web/DESIGN.md "Motion": cumulative layout shift 0). Each screen is opened
 * afresh with the API slowed down, so its skeleton paints first and the data replaces it; the font swap counts
 * too. Measured in every screens project: phone and laptop, light and dark.
 */
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
  { name: "hosting", path: "/hosting/", ready: () => "Add a machine" },
  { name: "a running machine", path: `/hosting/machine/?id=${hosting.ROG.id}`, ready: () => "Pause hosting" },
  {
    name: "a machine's price",
    path: `/hosting/price/?id=${hosting.ROG.id}`,
    ready: () => "One session at a time",
  },
  {
    name: "what a machine ran",
    path: `/hosting/sessions/?id=${hosting.ROG.id}`,
    ready: () => "Fine-tuning · 18 min",
  },
  { name: "earnings", path: "/hosting/earnings/", ready: () => "Given back for lost time" },
  // The payouts' tiles arrive after the paint, each drawn first at its own height (ADR 0040).
  { name: "earnings, with payouts", path: "/hosting/earnings/", ready: () => "Didn't go through" },
  { name: "payout details", path: "/hosting/payout-details/", ready: () => "Payouts go here" },
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
  { name: "adding a machine", path: "/hosting/add/", ready: () => "Windows app: coming soon" },
  { name: "linking a laptop", path: `/link/${hosting.LINK_ID}/`, ready: () => "Approve and start earning" },
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
    await signIn(page, "asha");
    await page.unrouteAll({ behavior: "wait" });
    await fakeApi(page, answers, { delayMs: 600 });
    const shifts = await recordLayoutShifts(page);
    await page.goto(screen.path);
    await expect(page.getByText(screen.ready()).first()).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(1000);
    const { total, sources } = await shifts();
    expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
  });
}
