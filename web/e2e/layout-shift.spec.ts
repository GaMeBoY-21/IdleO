import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { fakeApi, recordLayoutShifts, signIn } from "./support";

/**
 * No screen moves while it loads (web/DESIGN.md "Motion": cumulative layout shift 0). Each screen is opened
 * afresh with the API slowed down, so its skeleton paints first and the data replaces it; the font swap counts
 * too. Measured in every screens project: phone and laptop, light and dark.
 */
const screens = [
  { name: "home", path: "/", ready: () => "Running now" },
  { name: "credit", path: "/credit/", ready: () => "Show older" },
  { name: "session detail", path: `/session/?id=${data.completed.id}`, ready: () => "result.json" },
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
    await fakeApi(page, {
      "/v1/me": { json: data.me() },
      "/v1/templates": { json: data.templates },
      "/v1/wallet": { json: data.wallet },
      "/v1/sessions": { json: { sessions: [data.running, data.completed] } },
      "/v1/sessions/[^/]+/outputs": { json: data.outputs },
      "/v1/sessions/[^/]+": { json: data.completed },
      "/v1/wallet/transactions": { json: data.history(0) },
    });
    await signIn(page, "asha");
    await page.unrouteAll({ behavior: "wait" });
    await fakeApi(
      page,
      {
        "/v1/me": { json: data.me() },
        "/v1/templates": { json: data.templates },
        "/v1/wallet": { json: data.wallet },
        "/v1/sessions": { json: { sessions: [data.running, data.completed] } },
        "/v1/sessions/[^/]+/outputs": { json: data.outputs },
        "/v1/sessions/[^/]+": { json: data.completed },
        "/v1/wallet/transactions": { json: data.history(0) },
      },
      { delayMs: 600 },
    );
    const shifts = await recordLayoutShifts(page);
    await page.goto(screen.path);
    await expect(page.getByText(screen.ready()).first()).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(1000);
    const { total, sources } = await shifts();
    expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
  });
}
