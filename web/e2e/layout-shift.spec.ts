import { expect, test, type Request } from "@playwright/test";
import * as data from "./fixtures";
import * as hosting from "./fixtures-hosting";
import { hostingApi } from "./hosting-api";
import { fakeApi, recordLayoutShifts, signIn } from "./support";

/**
 * No screen moves while it loads (web/DESIGN.md "Motion": cumulative layout shift 0). Each screen is opened
 * afresh with the API slowed down, so its skeleton paints first and the data replaces it; the font swap counts
 * too. Measured in every screens project: phone and laptop, light and dark.
 */
const screens = [
  { name: "home", path: "/", ready: () => "Book again" },
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
  { name: "account", path: "/account/", ready: () => "New device sign-in" },
  {
    name: "adding a machine",
    path: `/hosting/add/?id=${hosting.hostNew.id}`,
    ready: () => "Save and finish",
  },
];

test("sign-in loads without moving, its form arriving in the space kept for it", async ({ page }) => {
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
      [`/v1/sessions/${data.running.id}`]: { json: data.running },
      [`/v1/sessions/${data.finding.id}`]: { json: data.finding },
      "/v1/uploads/[^/]+": { json: data.sessionUpload },
      "/v1/uploads": { json: data.uploadsPage() },
      "/v1/session-quotes": (_call: number, request: Request) =>
        data.quoteAnswer(request.postDataJSON() as { maxSpendPaise: number; vramNeededMb?: number }),
      "/v1/sessions/[^/]+": { json: data.completed },
      "/v1/wallet/transactions": { json: data.history(0) },
    };
    await fakeApi(page, routes);
    await signIn(page, "asha");
    await page.unrouteAll({ behavior: "wait" });
    await fakeApi(page, routes, { delayMs: 600 });
    const shifts = await recordLayoutShifts(page);
    await page.goto(screen.path);
    await expect(page.getByText(screen.ready()).first()).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(1000);
    const { total, sources } = await shifts();
    expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
  });
}
