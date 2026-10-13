import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import * as trust from "./fixtures-trust";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * Account → Reports, and a refunded report in the credit history (ADR 0028), every state, light and dark, phone and
 * laptop: zero axe violations, no console errors. The API answers from fixtures.
 */

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.refunded.wallet },
  "/v1/sessions": { json: data.sessionsPage(data.refunded.sessions) },
  "/v1/disputes": { json: trust.disputesPage() },
  ...overrides,
});

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test.describe("Reports", () => {
  test("lists each report, newest first, with where it stands and our reason", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByRole("heading", { level: 1, name: "Reports" })).toBeVisible();
    const items = page.getByRole("listitem").filter({ hasText: "Sent" });
    await expect(items).toHaveCount(2);
    await expect(items.nth(0)).toContainText("It was slow");
    await expect(items.nth(0)).toContainText("Report resolved, no refund");
    await expect(items.nth(0)).toContainText("It ran at the speed of its GPU.");
    await expect(items.nth(1)).toContainText("The output was wrong");
    await expect(items.nth(1)).toContainText("Report resolved: ₹1.00 refunded");
    await expect(items.nth(1)).toContainText("The adapter was empty");
    await expectAccessible(page);
    await capture(page, info, "reports");
  });

  test("opens the session a report is about", async ({ page }) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/sessions/[^/]+": { json: data.reportedSession },
        "/v1/sessions/[^/]+/events": {
          events: `event: session\ndata: ${JSON.stringify(data.reportedSession)}\n\n`,
        },
        "/v1/sessions/[^/]+/rating": { json: trust.ratingOpen(data.reportedSession) },
        "/v1/sessions/[^/]+/outputs": { json: data.outputs },
        "/v1/uploads/[^/]+": { json: data.sessionUpload },
      }),
    );
    await signIn(page, "ravi", "/account/reports/");
    await page.getByRole("link", { name: /The output was wrong/ }).click();
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${data.reportedSession.id}$`));
  });

  test("an open report says we'll reply within 2 days", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/disputes": { json: trust.disputesPage([trust.disputeOpen(data.failed)]) } }),
    );
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByText("Your report is open")).toBeVisible();
    await expect(page.getByText("A person reads every report. We'll reply within 2 days.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "reports-open");
  });

  test("is reached from Account", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "ravi", "/account/");
    await page.getByRole("link", { name: /Reports/ }).click();
    await expect(page).toHaveURL(/\/account\/reports\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "Reports" })).toBeVisible();
  });

  test("while the reports load", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/disputes": "hold" }));
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByRole("status", { name: "Loading your reports" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "reports-loading");
  });

  test("when they can't be loaded, with a retry that works", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/disputes": { status: 503 } }));
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByText(/couldn't load your reports/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "reports-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, signedIn());
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("It was slow")).toBeVisible();
  });

  test("with no reports yet", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/disputes": { json: trust.disputesPage([]) } }));
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByText("No reports")).toBeVisible();
    await expect(page.getByText(/choose Report a problem/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "reports-empty");
  });

  test("keeps the reports and says so when a refresh fails", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/disputes": (call) => (call === 1 ? { json: trust.disputesPage() } : { status: 503 }),
      }),
    );
    await signIn(page, "ravi", "/account/reports/");
    await expect(page.getByText("It was slow")).toBeVisible();
    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    await expect(page.getByText(/Couldn't refresh/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("It was slow")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "reports-stale");
  });
});

test.describe("the credit history", () => {
  test("shows a refund after a report as a credit, linked to its session", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/wallet/transactions": { json: data.history(0, data.refunded) },
      }),
    );
    await signIn(page, "ravi", "/credit/");
    const row = page.getByRole("link", { name: /Refund after your report/ });
    await expect(row).toContainText("+₹1.00");
    await expect(row).toHaveAttribute("href", `/session/?id=${data.reportedSession.id}`);
    await expectAccessible(page);
    await capture(page, info, "credit-report-refund");
  });
});
