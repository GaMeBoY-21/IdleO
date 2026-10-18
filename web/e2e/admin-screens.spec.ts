import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import * as trust from "./fixtures-trust";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * The admin console (ADR 0027), every screen and its states, light and dark, phone and laptop: zero axe violations,
 * no console errors. The API answers from fixtures; the journeys against the real stack are admin-journey.spec.ts.
 */

const routes = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  "/admin/v1/session": { json: admin.inside },
  "/admin/v1/dashboard": { json: admin.dashboard },
  "/admin/v1/installs": { json: admin.installs },
  "/admin/v1/users": { json: admin.users },
  [`/admin/v1/users/${admin.RENTER_ID}`]: { json: admin.renter },
  "/admin/v1/credit-grants": { json: admin.grants },
  "/admin/v1/hosts": { json: admin.hosts },
  [`/admin/v1/hosts/${admin.HOST_ID}`]: { json: admin.host },
  [`/admin/v1/hosts/${admin.PENDING_HOST_ID}`]: { json: admin.pendingHost },
  "/admin/v1/sessions": { json: admin.sessions },
  [`/admin/v1/sessions/${admin.SESSION_ID}`]: { json: admin.session },
  "/admin/v1/review-minutes": { json: admin.reviewMinutes },
  "/admin/v1/disputes": { json: trust.adminDisputes() },
  [`/admin/v1/disputes/${trust.disputeOpen(data.failed).id}`]: {
    json: trust.adminDispute(trust.disputeOpen(data.failed), data.me().id, data.failed),
  },
  [`/admin/v1/disputes/${trust.disputeRefunded.id}`]: {
    json: trust.adminDispute(trust.disputeRefunded, trust.RAVI, data.reportedSession),
  },
  [`/admin/v1/sessions/${data.failed.id}`]: { json: trust.adminSessionOf(data.failed, data.me().id) },
  [`/admin/v1/sessions/${data.failed.id}/usage`]: {
    json: trust.adminUsage(data.failed, { agentOnlyExtra: true }),
  },
  [`/admin/v1/sessions/${data.reportedSession.id}`]: {
    json: trust.adminSessionOf(data.reportedSession, trust.RAVI),
  },
  [`/admin/v1/sessions/${data.reportedSession.id}/usage`]: {
    json: trust.adminUsage(data.reportedSession, { refundedPaise: data.REPORT_REFUND }),
  },
  "/admin/v1/reconciliation": { json: admin.reconciliation },
  "/admin/v1/ledger": { json: admin.ledger },
  "/admin/v1/invites": { json: admin.invites },
  "/admin/v1/audit": { json: admin.audit },
  ...overrides,
});

async function open(page: Page, path: string, overrides: Record<string, Answer> = {}) {
  await fakeApi(page, routes(overrides));
  await signIn(page, "asha", path);
}

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test.describe("the way in", () => {
  test("is a page that isn't there for anyone who isn't an admin", async ({ page }, info) => {
    await open(page, "/admin/", { "/admin/v1/session": { status: 404 } });
    await expect(page.getByRole("heading", { level: 1, name: "We can't find that page" })).toBeVisible();
    await expect(page.getByText("Admin")).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "admin-not-found");
  });

  test("sets up the authenticator once, with a QR code, then shows the recovery codes once", async ({
    page,
  }, info) => {
    let set = false;
    await open(page, "/admin/", {
      "/admin/v1/session": () => ({ json: set ? admin.inside : admin.notEnrolled }),
      "/admin/v1/second-factor": { json: admin.enrolment, status: 201 },
      "/admin/v1/second-factor/confirm": (_call, request) => {
        expect(request.postDataJSON()).toEqual({ totp: "287082" });
        set = true;
        return { json: admin.confirmed };
      },
    });
    await expect(page.getByRole("heading", { level: 1, name: "Set up your authenticator" })).toBeVisible();
    await expect(page.getByRole("img", { name: "QR code of your authenticator secret" })).toBeVisible();
    await expect(page.getByText(admin.enrolment.secret)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-enrol");

    const field = page.getByLabel("Code from your authenticator app");
    await field.fill("2870");
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page.getByText("Type the 6 digits your app shows.")).toBeVisible();
    await field.fill("287 082");
    await page.getByRole("button", { name: "Confirm" }).click();

    await expect(page.getByRole("heading", { level: 1, name: "Save your recovery codes" })).toBeVisible();
    await expect(page.getByRole("list", { name: "Recovery codes" }).getByRole("listitem")).toHaveCount(10);
    await expectAccessible(page);
    await capture(page, info, "admin-recovery-codes");
    await page.getByRole("button", { name: "I've saved them" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
  });

  test("asks for a code, or a recovery code, and says when one is wrong", async ({ page }, info) => {
    await open(page, "/admin/", { "/admin/v1/session": { json: admin.enrolled } });
    await expect(page.getByRole("heading", { level: 1, name: "Admin sign-in" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-check");
    await page.getByRole("button", { name: "Use a recovery code instead" }).click();
    await expect(page.getByLabel("Recovery code")).toBeVisible();
    await page.getByLabel("Recovery code").fill("K7Q2M-9XD4");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByText("A recovery code is 10 letters and digits.")).toBeVisible();
  });

  test("shows the server's words when a code is refused", async ({ page }, info) => {
    await open(page, "/admin/", {
      "/admin/v1/session": (_call, request) =>
        request.method() === "POST"
          ? { status: 400, problem: "admin-code-invalid" }
          : { json: admin.enrolled },
    });
    await page.getByLabel("Code from your authenticator app").fill("123456");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByRole("alert")).toContainText("That code isn't right, or was already used.");
    await expectAccessible(page);
    await capture(page, info, "admin-check-wrong");
  });
});

test.describe("the console", () => {
  test("opens on the dashboard, its figures counted by each module", async ({ page }, info) => {
    await open(page, "/admin/");
    await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Admin" }).getByRole("link")).toHaveCount(9);
    const figures = page.getByRole("list", { name: "Figures" }).getByRole("listitem");
    await expect(figures.filter({ hasText: "Hosts online" })).toContainText(
      String(admin.dashboard.hostsOnline),
    );
    await expect(figures.filter({ hasText: "Review queue" })).toContainText(
      String(admin.dashboard.reviewQueue),
    );
    // The Installs card: the median minutes to listed, the last installs, and the failures by reason.
    await expect(page.getByText(`${admin.installs.medianMinutes!.toFixed(1)} min`).first()).toBeVisible();
    await expect(page.getByRole("table", { name: "The last 20 installs" }).getByRole("row")).toHaveCount(
      admin.installs.recent.length + 1,
    );
    await expect(page.getByRole("table", { name: "Failures by reason" })).toContainText("driver_too_old");
    // The words behind a failure open under their install; an install that didn't fail has none.
    const stuck = admin.installs.recent.find((install) => install.details.length > 0)!;
    const toggle = page.getByRole("button", { name: "1 reason" });
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await expect(page.getByText(stuck.details[0]!.detail)).toBeHidden();
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByText(stuck.details[0]!.detail)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-dashboard");
  });

  test("while the dashboard loads", async ({ page }, info) => {
    await open(page, "/admin/", { "/admin/v1/dashboard": "hold" });
    await expect(page.getByRole("status", { name: "Loading the dashboard" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-dashboard-loading");
  });

  test("when the dashboard can't be loaded, with a retry that works", async ({ page }, info) => {
    await open(page, "/admin/", { "/admin/v1/dashboard": { status: 503 } });
    await expect(page.getByText(/couldn't load the dashboard/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "admin-dashboard-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, routes());
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("list", { name: "Figures" })).toBeVisible();
  });

  test("finds users", async ({ page }, info) => {
    await open(page, "/admin/users/");
    await expect(page.getByRole("table", { name: "Users" }).getByRole("row")).toHaveCount(3);
    await expectAccessible(page);
    await capture(page, info, "admin-users");
  });

  test("says when no user matches", async ({ page }, info) => {
    await open(page, "/admin/users/", { "/admin/v1/users": { json: { users: [], nextCursor: null } } });
    await expect(page.getByText("No users match")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-users-empty");
  });

  test("opens a user, and suspending asks why first", async ({ page }, info) => {
    let sent: unknown = null;
    await open(page, `/admin/users/?id=${admin.RENTER_ID}`, {
      [`/admin/v1/users/${admin.RENTER_ID}/suspend`]: (_call, request) => {
        sent = request.postDataJSON();
        return { json: { ...admin.renter, status: "suspended" } };
      },
    });
    await expect(page.getByText(admin.renter.email).first()).toBeVisible();
    await expect(page.getByRole("table", { name: "Credit grants" })).toContainText("₹500.00");
    await expectAccessible(page);
    await capture(page, info, "admin-user");

    await page.getByRole("button", { name: "Suspend" }).click();
    const sheet = page.getByRole("dialog", { name: `Suspend ${admin.renter.email}?` });
    await expect(sheet).toContainText("their sign-ins and API keys are refused");
    await sheet.getByLabel("Reason").fill("spam");
    await sheet.getByRole("button", { name: "Suspend" }).click();
    await expect(sheet.getByText(/at least 5 characters/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-user-suspend");
    await sheet.getByLabel("Reason").fill("card testing from this account");
    await sheet.getByRole("button", { name: "Suspend" }).click();
    await expect(sheet).toBeHidden();
    expect(sent).toEqual({ reason: "card testing from this account" });
  });

  test("opens a pending host, and shows the server's reason when activating is refused", async ({
    page,
  }, info) => {
    await open(page, `/admin/hosts/?id=${admin.PENDING_HOST_ID}`, {
      [`/admin/v1/hosts/${admin.PENDING_HOST_ID}/activate`]: { status: 409, problem: "action-refused" },
    });
    await expect(page.getByText("pending").first()).toBeVisible();
    await expect(page.getByRole("table", { name: "Certificates" })).toBeVisible();
    // Its self-test sits beside Activate: the pilot's admin decides with it in front of them.
    await expect(page.getByRole("table", { name: /^Self-test failed/ })).toContainText(
      "nvidia-smi found no GPU",
    );
    await expect(page.getByRole("table", { name: "GPUs now" })).toContainText("71 °C");
    await expectAccessible(page);
    await capture(page, info, "admin-host");
    await page.getByRole("button", { name: "Activate" }).click();
    const sheet = page.getByRole("dialog", { name: `Activate ${admin.pendingHost.name}?` });
    await sheet.getByLabel("Reason").fill("passed the canary job");
    await sheet.getByRole("button", { name: "Activate" }).click();
    await expect(sheet.getByRole("alert")).toContainText("A fixture error.");
    await expectAccessible(page);
    await capture(page, info, "admin-host-refused");
  });

  test("lists hosts", async ({ page }, info) => {
    await open(page, "/admin/hosts/");
    await expect(page.getByRole("table", { name: "Hosts" }).getByRole("row")).toHaveCount(3);
    await expectAccessible(page);
    await capture(page, info, "admin-hosts");
  });

  test("opens a running session with its timeline, and a force stop says what it charges", async ({
    page,
  }, info) => {
    await open(page, `/admin/sessions/?id=${admin.SESSION_ID}`);
    await expect(page.getByRole("heading", { level: 2, name: "Timeline" })).toBeVisible();
    await expect(page.getByRole("table", { name: "Attempts" }).getByRole("row")).toHaveCount(2);
    await expectAccessible(page);
    await capture(page, info, "admin-session");
    await page.getByRole("button", { name: "Force stop" }).click();
    const sheet = page.getByRole("dialog", { name: "Force-stop this session?" });
    await expect(sheet).toContainText("₹1.23 so far");
    await expectAccessible(page);
    await capture(page, info, "admin-session-stop");
  });

  test("lists sessions", async ({ page }, info) => {
    await open(page, "/admin/sessions/");
    await expect(page.getByRole("table", { name: "Sessions" })).toContainText("fine-tune-lora");
    await expectAccessible(page);
    await capture(page, info, "admin-sessions");
  });

  test("works the review queue", async ({ page }, info) => {
    await open(page, "/admin/review/");
    await expect(page.getByRole("table", { name: "Minutes in review" })).toContainText(
      "Only the agent reported it",
    );
    await expectAccessible(page);
    await capture(page, info, "admin-review");
    await page.getByRole("button", { name: /^Charge/ }).click();
    await expect(page.getByRole("dialog", { name: "Charge minute 41?" })).toContainText("₹0.03");
  });

  test("says when the review queue is empty", async ({ page }, info) => {
    await open(page, "/admin/review/", {
      "/admin/v1/review-minutes": { json: { minutes: [], nextCursor: null } },
    });
    await expect(page.getByText("Nothing to review")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-review-empty");
  });

  test("works the disputes queue: open reports first, then resolved ones", async ({ page }, info) => {
    await open(page, "/admin/disputes/");
    const table = page.getByRole("table", { name: "Reports" });
    const rows = table.getByRole("row");
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(1)).toContainText("Open");
    await expect(rows.nth(1)).toContainText("It failed");
    await expect(rows.nth(2)).toContainText("Refunded");
    await expect(rows.nth(2)).toContainText("₹1.00");
    await expect(rows.nth(3)).toContainText("No refund");
    await expectAccessible(page);
    await capture(page, info, "admin-disputes");
  });

  test("says when there are no reports to answer", async ({ page }, info) => {
    await open(page, "/admin/disputes/", {
      "/admin/v1/disputes": { json: { disputes: [], nextCursor: null } },
    });
    await expect(page.getByText("No reports to answer")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-disputes-empty");
  });

  test("opens an open report with the session's evidence, and refunds part of it", async ({ page }, info) => {
    const report = trust.disputeOpen(data.failed);
    let body: { amountPaise: number; reason: string } | null = null;
    await open(page, `/admin/disputes/?id=${report.id}`, {
      [`/admin/v1/disputes/${report.id}/refund`]: (_call, request) => {
        body = request.postDataJSON() as { amountPaise: number; reason: string };
        return {
          json: trust.adminDispute(
            { ...report, state: "refunded", refundPaise: 50 },
            data.me().id,
            data.failed,
          ),
        };
      },
    });
    await expect(page.getByRole("heading", { level: 1, name: "Report" })).toBeVisible();
    await expect(page.getByText("It stopped after 20 minutes with no error in the log.")).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Timeline" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Minutes and charges" })).toBeVisible();
    // Both witnesses saw every charged minute; one more only the agent reported, so it wasn't charged.
    await expect(
      page.getByRole("table", { name: "Minutes that weren't charged in the usual way" }),
    ).toContainText("not covered");
    await expect(page.getByRole("table", { name: "Settlements" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-dispute");

    await page.getByRole("button", { name: "Refund", exact: true }).click();
    const sheet = page.getByRole("dialog", { name: "Refund this report?" });
    await expect(sheet).toContainText("It comes out of the platform's commission first");
    await expect(sheet).toContainText("₹0.20 kept on this session");
    // More than it cost is refused before anything is sent.
    await sheet.getByLabel("Amount in ₹").fill("99");
    await sheet.getByLabel("Reason").fill("The job failed on our host.");
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toContainText("can't be more than the session cost");
    expect(body).toBeNull();
    await expectAccessible(page);
    await capture(page, info, "admin-dispute-refund");
    await sheet.getByLabel("Amount in ₹").fill("0.50");
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toBeHidden();
    expect(body).toEqual({ amountPaise: 50, reason: "The job failed on our host." });
  });

  test("closes a report without a refund, with a reason the renter reads", async ({ page }) => {
    const report = trust.disputeOpen(data.failed);
    let body: { reason: string } | null = null;
    await open(page, `/admin/disputes/?id=${report.id}`, {
      [`/admin/v1/disputes/${report.id}/no-refund`]: (_call, request) => {
        body = request.postDataJSON() as { reason: string };
        return { json: trust.adminDispute({ ...report, state: "not_refunded" }, data.me().id, data.failed) };
      },
    });
    await page.getByRole("button", { name: "No refund" }).click();
    const sheet = page.getByRole("dialog", { name: "Close this report without a refund?" });
    await expect(sheet).toContainText("The renter reads your reason");
    await sheet.getByLabel("Reason").fill("It ran out of memory: the model was too big.");
    await sheet.getByRole("button", { name: "Close without a refund" }).click();
    await expect(sheet).toBeHidden();
    expect(body).toEqual({ reason: "It ran out of memory: the model was too big." });
  });

  test("shows a refunded report: who decided, and where the money came from", async ({ page }, info) => {
    await open(page, `/admin/disputes/?id=${trust.disputeRefunded.id}`);
    await expect(page.getByRole("heading", { level: 2, name: "How it was decided" })).toBeVisible();
    await expect(page.getByText("From the commission")).toBeVisible();
    await expect(page.getByText("The adapter file was empty and I paid for the whole run.")).toBeVisible();
    // No more actions on a resolved report.
    await expect(page.getByRole("button", { name: "Refund", exact: true })).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "admin-dispute-resolved");
  });

  test("while a report loads, and when it can't", async ({ page }, info) => {
    await open(page, "/admin/disputes/", { "/admin/v1/disputes": "hold" });
    await expect(page.getByRole("status", { name: "Loading the disputes" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-disputes-loading");
  });

  test("shows the reconciliation runs and a wallet in the ledger", async ({ page }, info) => {
    await open(page, `/admin/money/?owner=${admin.RENTER_ID}`);
    await expect(page.getByRole("table", { name: "Reconciliation runs" })).toContainText("balanced");
    await expect(page.getByRole("table", { name: "Ledger entries" })).toContainText("credit_grant");
    await expectAccessible(page);
    await capture(page, info, "admin-money");
  });

  test("makes invite codes and shows them once", async ({ page }, info) => {
    await open(page, "/admin/invites/", {
      "/admin/v1/invites": (_call, request) =>
        request.method() === "POST"
          ? { json: { codes: ["IDLE-7K2Q-9XMD", "IDLE-3RTW-8HJN"], expiresAt: null }, status: 201 }
          : { json: admin.invites },
    });
    await expect(page.getByRole("table", { name: "Invite codes" })).toContainText(admin.renter.email);
    await page.getByRole("button", { name: "Make invite codes" }).click();
    const sheet = page.getByRole("dialog", { name: "Make invite codes" });
    await sheet.getByLabel("How many (1 to 200)").fill("2");
    await sheet.getByLabel("Reason").fill("Second pilot batch");
    await sheet.getByRole("button", { name: "Make the codes" }).click();
    await expect(page.getByRole("list", { name: "New invite codes" }).getByRole("listitem")).toHaveCount(2);
    await expectAccessible(page);
    await capture(page, info, "admin-invites");
  });

  test("reads the audit log", async ({ page }, info) => {
    await open(page, "/admin/audit/");
    await expect(page.getByRole("table", { name: "Audit log" }).getByRole("row")).toHaveCount(3);
    await expect(page.getByRole("table", { name: "Audit log" })).toContainText("host.activate");
    await expectAccessible(page);
    await capture(page, info, "admin-audit");
  });
});
