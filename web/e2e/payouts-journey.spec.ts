import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { expectAccessible, signIn, watchConsole } from "./support";

/**
 * Money out on the real stack (ADR 0040, scripts/e2e/agent-online.sh), in two steps the script runs around its own:
 *
 * - `verify`: E2E_PAYOUTS_USER, who owns machines that have earned, gives a PAN (the fake PAN check verifies it), checks
 *   a UPI ID through the fake RazorpayX, reads the name the bank has for it, and uses it.
 * - `paid`: after the script's payout run and a report refunded after it, Earnings shows the payout as paid, the
 *   refund as recovered, and the year's statement downloads as a PDF; changing the UPI ID then holds payouts, and the
 *   screen says so.
 */
const who = process.env.E2E_PAYOUTS_USER ?? "";
const step = process.env.E2E_PAYOUTS_STEP ?? "verify";

test.describe.configure({ mode: "serial" });

let finish: () => void;
test.beforeEach(({ page }) => {
  finish = watchConsole(page);
});
test.afterEach(() => finish());

test("gives a PAN, checks a UPI ID with the bank's name for it, and uses it", async ({ page }) => {
  test.skip(step !== "verify");
  await signIn(page, who, "/hosting/payout-details/");
  await expect(page.getByRole("heading", { name: "Payout details", level: 1 })).toBeVisible();
  await page.getByRole("textbox", { name: "PAN", exact: true }).fill("ABCPE4321F");
  await page.getByLabel("Name exactly as on your PAN").fill("Nikki Host");
  await page.getByRole("button", { name: "Save PAN" }).click();
  await expect(page.getByText("Verified")).toBeVisible();
  await expect(page.getByText("••••••321F · Nikki Host")).toBeVisible();

  await page.getByLabel("Your UPI ID").fill("e2e.host@okicici");
  await page.getByRole("button", { name: "Check it" }).click();
  await expect(page.getByRole("status").filter({ hasText: "is registered to Nikki Host" })).toBeVisible({
    timeout: 15_000,
  });
  await expectAccessible(page);
  await page.getByRole("button", { name: "Use this UPI ID" }).click();
  await expect(page.getByText("Payouts go here")).toBeVisible();
  await expect(page.locator("main")).not.toContainText("ABCPE4321F");
});

test("shows the payout paid, the refund recovered, and the year's statement", async ({ page }) => {
  test.skip(step !== "paid");
  await signIn(page, who, "/hosting/earnings/");
  const history = page.getByRole("region", { name: "Payout history" });
  await expect(history).toContainText("Paid", { timeout: 15_000 });
  await expect(page.getByText(/^Recovered: refund for session [0-9a-f]{8} on /).first()).toBeVisible();
  await expectAccessible(page);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("region", { name: "Yearly statement" }).getByRole("button", { name: "PDF" }).click(),
  ]);
  expect(
    readFileSync(await download.path())
      .subarray(0, 5)
      .toString(),
  ).toBe("%PDF-");
});

test("changing the UPI ID holds payouts, and the screen says so", async ({ page }) => {
  test.skip(step !== "paid");
  await signIn(page, who, "/hosting/payout-details/");
  await page.getByLabel("Change your UPI ID").fill("e2e.changed@ybl");
  await page.getByRole("button", { name: "Check it" }).click();
  await expect(page.getByRole("status").filter({ hasText: "holds your next payout" })).toBeVisible({
    timeout: 15_000,
  });
  await page.getByRole("button", { name: "Use this UPI ID" }).click();
  await expect(page.getByText(/Your payout UPI ID was changed\. No payout goes to it before/)).toBeVisible();
  await expectAccessible(page);
});
