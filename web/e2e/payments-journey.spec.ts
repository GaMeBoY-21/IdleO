import { expect, test, type Page } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";
import { expectAccessible, signIn, watchConsole } from "./support";

/**
 * Money in on the real stack (ADR 0039, scripts/e2e/agent-online.sh): E2E_PAYMENTS_USER, who has no free credit,
 * gives their state, adds ₹500 through the fake Razorpay's Checkout (test UPI; the fake sends Razorpay's webhooks to
 * the control plane) and reads "₹500.00 added." only once the server has it, downloads the receipt, sees a payment
 * fail, waits through a confirmation Razorpay is slow with, and asks for ₹200 back. The seconds from pressing Pay to
 * "added", and the payment's ids, go to E2E_PAYMENTS_REPORT for the script's checks.
 */
const who = process.env.E2E_PAYMENTS_USER ?? "";
const report = process.env.E2E_PAYMENTS_REPORT ?? "";

test.describe.configure({ mode: "serial" });

let finish: () => void;
test.beforeEach(({ page }) => {
  finish = watchConsole(page);
});
test.afterEach(() => finish());

async function openCredit(page: Page) {
  await signIn(page, who, "/credit/");
  await expect(page.getByRole("heading", { name: "Credit", level: 1 })).toBeVisible();
}

/** Pays the chosen pack in the fake Checkout with `button` ("Pay with test UPI", "Fail the payment"). */
async function pay(page: Page, amount: string, button: string) {
  await page.getByRole("button", { name: amount, exact: true }).click();
  await page.getByRole("button", { name: `Pay ${amount}.00` }).click();
  const checkout = page.locator("[data-fake-checkout]");
  await expect(checkout).toBeVisible({ timeout: 15_000 });
  await checkout.getByRole("button", { name: button }).click();
}

const results: Record<string, unknown> = {};

test("adds ₹500 by test UPI, said only once the server has it, with its receipt", async ({ page }) => {
  await openCredit(page);
  await expect(page.getByRole("button", { name: "Pay ₹500.00" })).toBeDisabled();
  await page.getByLabel("Your state").selectOption("29");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("Saved.")).toBeVisible();

  const pressed = Date.now();
  await pay(page, "₹500", "Pay with test UPI");
  await expect(page.getByText("₹500.00 added.")).toBeVisible({ timeout: 30_000 });
  results.secondsToCredit = (Date.now() - pressed) / 1000;
  await expect(page.getByText("Bought", { exact: true }).locator("..")).toContainText("₹500.00");
  await expectAccessible(page);

  const receipt = page.getByRole("button", { name: /^Download receipt / });
  await expect(receipt).toBeVisible({ timeout: 15_000 });
  const [download] = await Promise.all([page.waitForEvent("download"), receipt.click()]);
  const pdf = readFileSync(await download.path());
  expect(pdf.subarray(0, 5).toString()).toBe("%PDF-");
  results.receipt = download.suggestedFilename();
});

test("a payment that fails says why and adds nothing", async ({ page }) => {
  await openCredit(page);
  await pay(page, "₹100", "Fail the payment");
  await expect(page.getByRole("alert")).toContainText("No credit was added.", { timeout: 15_000 });
  await expect(page.getByText("Bought", { exact: true }).locator("..")).toContainText("₹500.00");
});

test("a confirmation Razorpay is slow with says so, keeps checking, and adds it once it's in", async ({
  page,
}) => {
  await openCredit(page);
  // The server's answers to this page are held back for 12 s, as if Razorpay were slow: the confirmation waits, and
  // every look at the top-up says pending, though the webhook credits it meanwhile.
  const until = Date.now() + 12_000;
  await page.route(/\/v1\/wallet\/topups\/[^/]+\/confirm$/, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, until - Date.now())));
    await route.continue();
  });
  await page.route(/\/v1\/wallet\/topups\/[^/]+$/, async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { status: string };
    await route.fulfill({ response, json: Date.now() < until ? { ...body, status: "pending" } : body });
  });
  await pay(page, "₹100", "Pay with test UPI");
  await expect(page.getByText(/taking longer than usual to confirm it/)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("₹100.00 added.")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Bought", { exact: true }).locator("..")).toContainText("₹600.00");
});

test("asks for ₹200 of the bought credit back", async ({ page }) => {
  await openCredit(page);
  await page.getByRole("button", { name: "Ask for a refund" }).click();
  await page.getByLabel("How much, in ₹").fill("200");
  await page.getByLabel(/Anything we should know/).fill("end-to-end refund request");
  await page.getByRole("button", { name: "Send request" }).click();
  await expect(page.getByText(/You asked for ₹200.00 back on .* We'll answer by email./)).toBeVisible();
  await expectAccessible(page);
  if (report) {
    writeFileSync(report, `${JSON.stringify(results, null, 2)}\n`);
  }
});
