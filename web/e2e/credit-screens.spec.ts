import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as credit from "./fixtures-credit";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * The Credit page's money in (ADR 0039) in every state: bought and free credit, Add credit with its packs, a payment
 * that's added only once the server says so, one that fails, one Razorpay is slow to confirm, billing details,
 * refunds and the documents. The API answers from fixtures, and Checkout's script from a stand-in of Razorpay's
 * documented shape.
 */
const routes = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: credit.withBought(data.asha.wallet) },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  "/v1/wallet/transactions": { json: data.history(0) },
  "/v1/wallet/billing-details": { json: credit.billingDetails },
  "/v1/wallet/invoices": { json: credit.invoices },
  "/v1/wallet/refund-requests": { json: credit.refundRequests },
  ...overrides,
});

async function open(
  page: Page,
  overrides: Record<string, Answer> = {},
  checkout: "paid" | "failed" | "closed" = "paid",
) {
  await page.route("**/v1/checkout.js", (route) =>
    route.fulfill({ contentType: "text/javascript", body: credit.checkoutStub(checkout) }),
  );
  await fakeApi(page, routes(overrides));
  await signIn(page, "asha", "/credit/");
  await expect(page.getByRole("heading", { name: "Credit", level: 1 })).toBeVisible();
}

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test("shows bought and free credit, Add credit, billing details, refunds and the documents", async ({
  page,
}, info) => {
  await open(page);
  await expect(page.getByText("Bought", { exact: true })).toBeVisible();
  await expect(page.getByText("Free", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Pay ₹500.00" })).toBeEnabled();
  await expect(page.getByText("Your receipt goes to you in Karnataka.")).toBeVisible();
  await expect(page.getByText("IDL2627-000042")).toBeVisible();
  await expect(page.getByText(/₹500.00 of the credit you bought can go back/)).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "credit-money-in");
});

test("adds credit only once the server has the captured payment", async ({ page }, info) => {
  let polls = 0;
  await open(page, {
    "/v1/wallet/topups": { json: credit.topUp("pending"), status: 201 },
    "/v1/wallet/topups/[^/]+/confirm": { json: credit.topUp("pending") },
    "/v1/wallet/topups/[^/]+": () => {
      polls += 1;
      return { json: credit.topUp(polls < 2 ? "pending" : "paid") };
    },
  });
  await page.getByRole("button", { name: "Pay ₹500.00" }).click();
  await expect(page.getByText("Adding ₹500.00…")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "credit-adding");
  await expect(page.getByText("₹500.00 added.")).toBeVisible({ timeout: 10_000 });
  await capture(page, info, "credit-added");
});

test("says when Razorpay is slow to confirm, and keeps checking", async ({ page }, info) => {
  await open(page, {
    "/v1/wallet/topups": { json: credit.topUp("pending"), status: 201 },
    "/v1/wallet/topups/[^/]+/confirm": { status: 503, problem: "payments-unavailable" },
    "/v1/wallet/topups/[^/]+": { json: credit.topUp("pending") },
  });
  await page.getByRole("button", { name: "Pay ₹500.00" }).click();
  await expect(page.getByText("Adding ₹500.00…")).toBeVisible();
  // The page's clock is fixed; moving it on is how ten seconds pass for it.
  await page.clock.setFixedTime(new Date(data.NOW.getTime() + 12_000));
  await expect(page.getByText(/taking longer than usual to confirm it/)).toBeVisible({ timeout: 10_000 });
  await expectAccessible(page);
  await capture(page, info, "credit-slow");
});

test("says why a payment failed, in Razorpay's words", async ({ page }, info) => {
  await open(page, { "/v1/wallet/topups": { json: credit.topUp("pending"), status: 201 } }, "failed");
  await page.getByRole("button", { name: "Pay ₹500.00" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Payment failed because the UPI ID is invalid. No credit was added.",
  );
  await expectAccessible(page);
  await capture(page, info, "credit-failed");
});

test("goes back to the start when Checkout is closed", async ({ page }) => {
  await open(page, { "/v1/wallet/topups": { json: credit.topUp("pending"), status: 201 } }, "closed");
  await page.getByRole("button", { name: "Pay ₹500.00" }).click();
  await expect(page.getByText(/Paid through Razorpay/)).toBeVisible();
});

test("asks for the renter's state before the first payment", async ({ page }, info) => {
  await open(page, { "/v1/wallet/billing-details": { json: credit.noBillingDetails } });
  await expect(page.getByRole("button", { name: "Pay ₹500.00" })).toBeDisabled();
  await expect(page.getByText(/Choose your state under/)).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "credit-no-state");
});

test("asks for a refund of bought credit", async ({ page }, info) => {
  await open(page, {
    "/v1/wallet/refund-requests": (_call, request) =>
      request.method() === "POST"
        ? {
            json: { id: "r1", status: "requested", requestedAt: data.NOW.toISOString(), ticketNumber: 18 },
            status: 201,
          }
        : { json: credit.refundRequests },
  });
  await page.getByRole("button", { name: "Ask for a refund" }).click();
  await page.getByLabel(/Anything we should know/).fill("I bought too much");
  await expectAccessible(page);
  await capture(page, info, "credit-refund-ask");
  await page.getByRole("button", { name: "Send request" }).click();
  await expect(page.getByRole("button", { name: "Send request" })).toHaveCount(0);
});

test("while its tiles load, and when they can't", async ({ page }, info) => {
  await open(page, {
    "/v1/wallet/billing-details": "hold",
    "/v1/wallet/invoices": { status: 503 },
    "/v1/wallet/refund-requests": "hold",
  });
  await expect(page.getByRole("status", { name: "Loading your billing details" })).toBeVisible();
  await expect(page.getByText(/couldn't load your invoices/)).toBeVisible({ timeout: 15_000 });
  await expectAccessible(page);
  await capture(page, info, "credit-loading");
});

test("with nothing bought and no documents yet", async ({ page }, info) => {
  await open(page, {
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/wallet/invoices": { json: credit.noInvoices },
    "/v1/wallet/refund-requests": { json: credit.noRefunds },
  });
  await expect(page.getByText(/its receipt is here to download/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Refund unused credit" })).toHaveCount(0);
  await expectAccessible(page);
  await capture(page, info, "credit-nothing-bought");
});
