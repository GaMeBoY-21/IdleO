import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import * as images from "./fixtures-images";
import * as support from "./fixtures-support";
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
  [`/admin/v1/hosts/${admin.HOST_ID}/self-test`]: { json: admin.hostSelfTest },
  [`/admin/v1/hosts/${admin.PENDING_HOST_ID}/self-test`]: { json: admin.hostSelfTest },
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
  "/admin/v1/abuse": { json: admin.abuseQueue },
  [`/admin/v1/abuse/${admin.ABUSE_OPEN_ID}`]: { json: admin.abuseDetail },
  [`/admin/v1/abuse/${admin.ABUSE_LEARNABLE_ID}`]: { json: admin.abuseLearnableDetail },
  "/admin/v1/abuse/blocklist-additions": { json: admin.blocklistAdditions },
  "/admin/v1/images": { json: images.adminImages() },
  "/admin/v1/tickets": { json: support.adminTickets() },
  [`/admin/v1/tickets/${support.PRIVACY_TICKET}`]: { json: support.adminThread() },
  [`/admin/v1/users/${data.me().id}/deletion`]: { json: support.adminDeletion() },
  "/admin/v1/payments": { json: admin.payments },
  "/admin/v1/payments/reconciliation": { json: admin.paymentReconciliation },
  "/admin/v1/refunds": { json: admin.creditRefunds },
  "/admin/v1/chargebacks": { json: admin.chargebacks },
  [`/admin/v1/chargebacks/${admin.CHARGEBACK_ID}`]: { json: admin.chargeback },
  "/admin/v1/invoices": { json: admin.invoices },
  "/admin/v1/payouts": { json: admin.adminPayouts },
  "/admin/v1/payouts/reconciliation": { json: admin.payoutReconciliation },
  "/admin/v1/payouts/verifications": { json: admin.payoutVerifications },
  "/admin/v1/clawbacks": { json: admin.clawbacks },
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
    await expect(page.getByRole("navigation", { name: "Admin" }).getByRole("link")).toHaveCount(21);
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
    expect(sent).toEqual({ reason: "card testing from this account", cause: "other" });
  });

  test("suspending for misconduct as a host says its payouts are held, and a held user shows why", async ({
    page,
  }, info) => {
    let sent: unknown = null;
    await open(page, `/admin/users/?id=${admin.RENTER_ID}`, {
      [`/admin/v1/users/${admin.RENTER_ID}/suspend`]: (_call, request) => {
        sent = request.postDataJSON();
        return { json: { ...admin.renter, status: "suspended", suspensionCause: "host_misconduct" } };
      },
    });
    await page.getByRole("button", { name: "Suspend" }).click();
    const sheet = page.getByRole("dialog", { name: `Suspend ${admin.renter.email}?` });
    await sheet.getByLabel("Why").selectOption("host_misconduct");
    await sheet.getByLabel("Reason").fill("ran renters' jobs on someone else's machine");
    await expectAccessible(page);
    await capture(page, info, "admin-user-suspend-host");
    await sheet.getByRole("button", { name: "Suspend" }).click();
    await expect(sheet).toBeHidden();
    expect(sent).toEqual({ reason: "ran renters' jobs on someone else's machine", cause: "host_misconduct" });
  });

  test("a user suspended for misconduct as a host says why", async ({ page }) => {
    await open(page, `/admin/users/?id=${admin.RENTER_ID}`, {
      [`/admin/v1/users/${admin.RENTER_ID}`]: {
        json: { ...admin.renter, status: "suspended", suspensionCause: "host_misconduct" },
      },
    });
    await expect(
      page.getByText("Misconduct as a host: their payouts are held until it is lifted"),
    ).toBeVisible();
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

  test("shows a host's score in numbers, and runs a self-test on it with a reason", async ({
    page,
  }, info) => {
    let sent: unknown = null;
    await open(page, `/admin/hosts/?id=${admin.HOST_ID}`, {
      [`/admin/v1/hosts/${admin.HOST_ID}/self-tests`]: (_call, request) => {
        sent = request.postDataJSON();
        return { json: admin.hostSelfTest };
      },
    });
    const score = page.locator("section", { has: page.getByRole("heading", { name: "Host score" }) });
    await expect(score).toContainText("0.812 (good)");
    await expect(score).toContainText("0.740, 64 Mbit/s");
    await expect(score).toContainText("14 finished, 1 lost to the host");
    await expect(score).toContainText("Upheld reports");
    await expectAccessible(page);
    await capture(page, info, "admin-host-score");

    await page.getByRole("button", { name: "Run self-test" }).click();
    const sheet = page.getByRole("dialog", { name: `Run a self-test on ${admin.host.name}?` });
    await sheet.getByLabel("Reason").fill("after a driver update");
    await sheet.getByRole("button", { name: "Run self-test" }).click();
    await expect(sheet).toBeHidden();
    expect(sent).toEqual({ reason: "after a driver update" });
  });

  test("a host with no score yet says so, and the button says why when the host can't run one", async ({
    page,
  }) => {
    await open(page, `/admin/hosts/?id=${admin.PENDING_HOST_ID}`, {
      [`/admin/v1/hosts/${admin.PENDING_HOST_ID}/self-test`]: {
        json: {
          ...admin.hostSelfTest,
          canRun: false,
          whyNot: { code: "host-offline", message: "This machine isn't connected right now." },
        },
      },
    });
    await expect(page.getByText("No score yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Run self-test" })).toBeDisabled();
    await expect(page.getByText("This machine isn't connected right now.")).toBeVisible();
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

  test("works the reports queue: open reports first, then resolved ones", async ({ page }, info) => {
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
    expect(body).toEqual({ amountPaise: 50, reason: "The job failed on our host.", flagHost: false });
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
    expect(body).toEqual({ reason: "It ran out of memory: the model was too big.", flagHost: false });
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
    await expect(page.getByRole("status", { name: "Loading the reports" })).toBeVisible();
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

  test("finds a payment and shows what the reconciliation found, accepted with a reason", async ({
    page,
  }, info) => {
    let accepted: unknown = null;
    await open(page, "/admin/payments/", {
      [`/admin/v1/payments/mismatches/${admin.MISMATCH_ID}/accept`]: (_call, request) => {
        accepted = request.postDataJSON();
        return { json: { ...admin.paymentReconciliation.mismatches[0], state: "accepted" } };
      },
    });
    await expect(page.getByRole("table", { name: "What disagrees" })).toContainText(
      "Authorised, not captured",
    );
    await expect(page.getByRole("table", { name: "Runs" })).toContainText("nightly");
    await page.getByLabel("Renter's id, top-up id, order or payment id").fill(admin.payment.orderId);
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByRole("table", { name: "Top-ups" })).toContainText(admin.payment.paymentId);
    await expectAccessible(page);
    await capture(page, info, "admin-payments");
    await page.getByRole("button", { name: /^Accept/ }).click();
    await page
      .getByRole("dialog", { name: "Accept this disagreement?" })
      .getByLabel("Reason")
      .fill("Razorpay refunds it by itself");
    await page.getByRole("dialog").getByRole("button", { name: "Accept" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    expect(accepted).toEqual({ reason: "Razorpay refunds it by itself" });
  });

  test("shows the runs, the payouts and the balance, and pays one person again with a reason", async ({
    page,
  }, info) => {
    let retried: unknown = null;
    await open(page, "/admin/payouts/", {
      "/admin/v1/payouts/retry": (_call, request) => {
        retried = request.postDataJSON();
        return { json: { payout: null, notPaid: "ON_HOLD" } };
      },
    });
    await expect(page.getByRole("table", { name: "Payouts" })).toContainText("bank_account_closed");
    await expect(page.getByRole("table", { name: "Runs" }).first()).toContainText("weekly");
    await expect(page.getByText("short")).toBeVisible();
    await expect(page.getByRole("table", { name: "What disagrees" })).toContainText("RazorpayX paid it");
    await expectAccessible(page);
    await capture(page, info, "admin-payouts");
    await page.getByRole("button", { name: /^Retry/ }).click();
    await page
      .getByRole("dialog", { name: "Pay one person now?" })
      .getByLabel("Reason")
      .fill("the bank is back up");
    await page.getByRole("dialog").getByRole("button", { name: "Pay now" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    expect(retried).toEqual({ userId: admin.OWNER_ID, reason: "the bank is back up" });
    await expect(page.getByText(/Not paid: their UPI ID changed/)).toBeVisible();
  });

  test("verifies a PAN and a UPI ID with reasons, and reads a PAN whole only with one", async ({
    page,
  }, info) => {
    let verified: unknown = null;
    await open(page, "/admin/verification/", {
      [`/admin/v1/payouts/verifications/pans/${admin.OWNER_ID}/verify`]: (_call, request) => {
        verified = request.postDataJSON();
        return { json: { ...admin.payoutVerifications.pans[0], state: "verified" } };
      },
    });
    await expect(page.getByRole("table", { name: "PANs waiting" })).toContainText("••••••234F");
    await expect(page.getByRole("table", { name: "UPI IDs waiting" })).toContainText("R K SHARMA");
    await expectAccessible(page);
    await capture(page, info, "admin-verification");
    await page.getByRole("button", { name: /^Verify/ }).click();
    await page
      .getByRole("dialog", { name: "Verify this PAN?" })
      .getByLabel("Reason")
      .fill("photo of the card matches");
    await page.getByRole("dialog").getByRole("button", { name: "Verify" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    expect(verified).toEqual({ reason: "photo of the card matches" });
  });

  test("waives what a host still owes into chargeback losses, with a reason", async ({ page }, info) => {
    let waived: unknown = null;
    await open(page, "/admin/clawbacks/", {
      [`/admin/v1/clawbacks/${admin.CLAWBACK_ID}/waive`]: (_call, request) => {
        waived = request.postDataJSON();
        return { json: { ...admin.clawbacks.clawbacks[0], state: "waived", outstandingPaise: 0 } };
      },
    });
    await expect(page.getByRole("table", { name: "Clawbacks" })).toContainText("owed");
    await expectAccessible(page);
    await capture(page, info, "admin-clawbacks");
    await page.getByRole("button", { name: /^Waive/ }).click();
    await page
      .getByRole("dialog", { name: "Waive what's still owed?" })
      .getByLabel("Reason")
      .fill("the host couldn't have known");
    await page.getByRole("dialog").getByRole("button", { name: "Waive" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    expect(waived).toEqual({ reason: "the host couldn't have known" });
  });

  test("exports a quarter's tax deducted at source with a reason", async ({ page }, info) => {
    await open(page, "/admin/tax/");
    await expect(page.getByRole("heading", { level: 1, name: "Tax" })).toBeVisible();
    await expect(page.getByLabel(/^Quarter/)).toHaveValue(/^\d{4}-\d{2}-Q[1-4]$/);
    await expectAccessible(page);
    await capture(page, info, "admin-tax");
  });

  test("approves a refund of bought credit, never more than the renter can have back", async ({
    page,
  }, info) => {
    let approved: unknown = null;
    await open(page, "/admin/refunds/", {
      [`/admin/v1/refunds/${admin.REFUND_ID}/approve`]: (_call, request) => {
        approved = request.postDataJSON();
        return { json: { ...admin.creditRefund, status: "refunding" } };
      },
    });
    const table = page.getByRole("table", { name: "Refunds of bought credit" });
    await expect(table).toContainText("I bought more than I need this month.");
    await expect(table).toContainText("message #17");
    await expectAccessible(page);
    await capture(page, info, "admin-refunds");
    await page.getByRole("button", { name: /^Approve/ }).click();
    const sheet = page.getByRole("dialog", { name: "Refund this bought credit?" });
    await expect(sheet.getByLabel("Amount in ₹")).toHaveValue("200.00");
    await sheet.getByLabel("Amount in ₹").fill("1000");
    await sheet.getByLabel("Reason").fill("renter asked, credit unused");
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toContainText("can't be more than");
    await sheet.getByLabel("Amount in ₹").fill("200");
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toBeHidden();
    expect(approved).toEqual({ amountPaise: 20_000, reason: "renter asked, credit unused" });
  });

  test("lists chargebacks by the date evidence is due, and opens one with its evidence", async ({
    page,
  }, info) => {
    await open(page, "/admin/chargebacks/");
    await expect(page.getByRole("table", { name: "Chargebacks" })).toContainText("₹150.00");
    await expectAccessible(page);
    await capture(page, info, "admin-chargebacks");
    await page.getByRole("link", { name: /Open chargeback/ }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Chargeback" })).toBeVisible();
    await expect(page.getByText("Submit the evidence in Razorpay's dashboard")).toBeVisible();
    await expect(page.getByRole("table", { name: "Sessions paid from this payment" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Read the log" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-chargeback");
  });

  test("finds a renter's documents and issues a credit note against one", async ({ page }, info) => {
    let noted: unknown = null;
    await open(page, "/admin/invoices/", {
      [`/admin/v1/invoices/${admin.RECEIPT_ID}/credit-notes`]: (_call, request) => {
        noted = request.postDataJSON();
        return { json: admin.receipt };
      },
    });
    await page.getByLabel("Or the renter's id").fill(admin.RENTER_ID);
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByRole("table", { name: "Invoices, receipts and credit notes" })).toContainText(
      "IDL2627-000042",
    );
    await expectAccessible(page);
    await capture(page, info, "admin-invoices");
    await page.getByRole("button", { name: /^Credit note/ }).click();
    const sheet = page.getByRole("dialog", { name: /Issue a credit note/ });
    await sheet.getByLabel("Amount in ₹, GST included").fill("50");
    await sheet.getByLabel("Reason").fill("charged for an hour that failed");
    await sheet.getByRole("button", { name: "Issue the credit note" }).click();
    await expect(sheet).toBeHidden();
    expect(noted).toEqual({ amountPaise: 5_000, reason: "charged for an hour that failed" });
  });

  test("reads a reported session's log only once the admin has said why, and can flag the host", async ({
    page,
  }) => {
    const report = trust.disputeOpen(data.failed);
    let flagged: unknown = null;
    await open(page, `/admin/disputes/?id=${report.id}`, {
      [`/admin/v1/sessions/${data.failed.id}/log`]: (_call, request) => {
        expect(new URL(request.url()).searchParams.get("reason")).toBe("judging the report on it");
        return { json: { chunks: [{ attempt: 1, seq: 1, text: "epoch 1 loss 2.31\n" }], nextCursor: null } };
      },
      [`/admin/v1/disputes/${report.id}/no-refund`]: (_call, request) => {
        flagged = request.postDataJSON();
        return { json: trust.adminDispute({ ...report, state: "not_refunded" }, data.me().id, data.failed) };
      },
    });
    await expect(page.getByText("epoch 1 loss")).toHaveCount(0);
    await page.getByRole("button", { name: "Read the log" }).click();
    await page
      .getByRole("dialog", { name: "Read the session's log?" })
      .getByLabel("Reason")
      .fill("judging the report on it");
    await page.getByRole("dialog").getByRole("button", { name: "Read it" }).click();
    await expect(page.getByText("epoch 1 loss 2.31")).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "The host's record" })).toBeVisible();
    await page.getByRole("button", { name: "No refund" }).click();
    const sheet = page.getByRole("dialog", { name: "Close this report without a refund?" });
    await sheet.getByLabel(/Flag the host's record/).check();
    await sheet.getByLabel("Reason").fill("The host stopped it twice.");
    await sheet.getByRole("button", { name: "Close without a refund" }).click();
    await expect(sheet).toBeHidden();
    expect(flagged).toEqual({ reason: "The host stopped it twice.", flagHost: true });
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

test.describe("abuse reviews", () => {
  test("lists what a runner flagged or stopped, open first, with what was done", async ({ page }, info) => {
    await open(page, "/admin/abuse/");
    await expect(page.getByRole("heading", { level: 1, name: "Abuse" })).toBeVisible();
    await expect(
      page.getByText("The job looked up a mining pool; Its numbers look like mining"),
    ).toBeVisible();
    await expect(
      page.getByText("The runner stopped the session and the account was suspended").first(),
    ).toBeVisible();
    await expect(page.getByText("Confirmed, banned")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-abuse");
  });

  test("shows an empty queue in words", async ({ page }) => {
    await open(page, "/admin/abuse/", { "/admin/v1/abuse": { json: { items: [], nextCursor: null } } });
    await expect(page.getByText("Nothing to look at")).toBeVisible();
    await expectAccessible(page);
  });

  test("opens a review with its signals, its minutes and its notes", async ({ page }, info) => {
    await open(page, `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`);
    await expect(page.getByRole("heading", { level: 1, name: "Abuse review" })).toBeVisible();
    await expect(page.getByText("— the runner stopped the session")).toBeVisible();
    await expect(page.getByRole("table", { name: "Behaviour" })).toBeVisible();
    await expect(page.getByText("Renter's account is three days old.")).toBeVisible();
    await expect(page.getByText("they stop no one")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-abuse-review");
  });

  test("clears a review and refunds the session, after a reason", async ({ page }) => {
    let cleared: Record<string, unknown> | null = null;
    await open(page, `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`, {
      [`/admin/v1/abuse/${admin.ABUSE_OPEN_ID}/clear`]: (call, request) => {
        cleared = request.postDataJSON() as Record<string, unknown>;
        return { json: admin.abuseDetail };
      },
    });
    await page.getByRole("button", { name: "Clear and refund" }).click();
    await page.getByLabel("Reason").fill("a notebook that renders, not a miner");
    await page.getByRole("button", { name: "Clear and refund" }).last().click();
    await expect.poll(() => cleared).toMatchObject({ reason: "a notebook that renders, not a miner" });
  });

  test("confirms and bans, after a reason", async ({ page }) => {
    let confirmed: Record<string, unknown> | null = null;
    await open(page, `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`, {
      [`/admin/v1/abuse/${admin.ABUSE_OPEN_ID}/confirm`]: (call, request) => {
        confirmed = request.postDataJSON() as Record<string, unknown>;
        return { json: admin.abuseDetail };
      },
    });
    await page.getByRole("button", { name: "Confirm and ban" }).click();
    await page.getByLabel("Reason").fill("a miner, seen by two people");
    await page.getByRole("button", { name: "Confirm and ban" }).last().click();
    await expect.poll(() => confirmed).toMatchObject({ ban: true, reason: "a miner, seen by two people" });
  });

  test("lists the builds waiting to go on the signed blocklist, and takes one back with a reason", async ({
    page,
  }, info) => {
    let removed: Record<string, unknown> | null = null;
    await open(page, "/admin/abuse/", {
      "/admin/v1/abuse/blocklist-additions/01a1340a-0000-7000-8000-00000000f002/remove": (call, request) => {
        removed = request.postDataJSON() as Record<string, unknown>;
        return { json: admin.noBlocklistAdditions };
      },
    });
    const table = page.getByRole("table", { name: "Blocklist additions" });
    await expect(table).toContainText("oldminer");
    await expect(table).toContainText("In the published list");
    await expect(table).toContainText("Waiting to be published");
    await expectAccessible(page);
    await capture(page, info, "admin-abuse-blocklist");
    await table.getByRole("button", { name: /Take back cracker/ }).click();
    await page.getByLabel("Reason").fill("it was a fine program");
    await page.getByRole("button", { name: "Take it back" }).last().click();
    await expect.poll(() => removed).toMatchObject({ reason: "it was a fine program" });
  });

  test("shows an empty list of additions in words", async ({ page }) => {
    await open(page, "/admin/abuse/", {
      "/admin/v1/abuse/blocklist-additions": { json: admin.noBlocklistAdditions },
    });
    await expect(page.getByText("Nothing waiting")).toBeVisible();
    await expectAccessible(page);
  });

  test("adds a flagged program's build to the blocklist from a confirmed review, after a reason", async ({
    page,
  }, info) => {
    let added: Record<string, unknown> | null = null;
    await open(page, `/admin/abuse/?id=${admin.ABUSE_LEARNABLE_ID}`, {
      [`/admin/v1/abuse/${admin.ABUSE_LEARNABLE_ID}/blocklist`]: (call, request) => {
        added = request.postDataJSON() as Record<string, unknown>;
        return { json: admin.blocklistAdditions };
      },
    });
    await expect(page.getByText("renamedminer").first()).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-abuse-learn");
    await page.getByRole("button", { name: /Add this program's hash to the blocklist/ }).click();
    const sheet = page.getByRole("dialog", { name: /Add renamedminer's build to the blocklist\?/ });
    await sheet.getByLabel("What it is").selectOption("password_cracker");
    await sheet.getByLabel("Reason").fill("seen mining on two hosts");
    await sheet.getByRole("button", { name: "Add to the blocklist" }).click();
    await expect
      .poll(() => added)
      .toMatchObject({
        sha256: admin.LEARNABLE_BUILD,
        kind: "password_cracker",
        reason: "seen mining on two hosts",
      });
  });

  test("an open review offers no blocklist action", async ({ page }) => {
    await open(page, `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`);
    await expect(page.getByRole("heading", { level: 1, name: "Abuse review" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Add this program's hash/ })).toHaveCount(0);
  });

  test("a note needs five characters, then is kept", async ({ page }) => {
    let noted: Record<string, unknown> | null = null;
    await open(page, `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`, {
      [`/admin/v1/abuse/${admin.ABUSE_OPEN_ID}/notes`]: (call, request) => {
        noted = request.postDataJSON() as Record<string, unknown>;
        return { json: admin.abuseDetail };
      },
    });
    await page.getByRole("textbox", { name: "Note" }).fill("hi");
    await page.getByRole("button", { name: "Add the note" }).click();
    await expect(page.getByText("Write at least 5 characters.")).toBeVisible();
    await page.getByRole("textbox", { name: "Note" }).fill("Asked the renter what it was.");
    await page.getByRole("button", { name: "Add the note" }).click();
    await expect.poll(() => noted).toMatchObject({ note: "Asked the renter what it was." });
  });
});

test.describe("tickets and notices", () => {
  test("lists tickets by due date with the overdue highlighted", async ({ page }, info) => {
    await open(page, "/admin/tickets/");
    await expect(page.getByRole("heading", { level: 1, name: "Tickets" })).toBeVisible();
    await expect(page.getByText(/^Overdue since /)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-tickets");
  });

  test("a privacy request is answered by email, and can start the person's export", async ({
    page,
  }, info) => {
    let answered: Record<string, unknown> | null = null;
    let exported = false;
    await open(page, `/admin/tickets/?id=${support.PRIVACY_TICKET}`, {
      [`/admin/v1/tickets/${support.PRIVACY_TICKET}/answer`]: (call, request) => {
        answered = request.postDataJSON() as Record<string, unknown>;
        return { json: support.adminThread() };
      },
      [`/admin/v1/users/${data.me().id}/exports`]: () => {
        exported = true;
        return { json: { id: "x", state: "requested", requestedAt: data.NOW.toISOString() } };
      },
    });
    await expect(page.getByText("Please send me everything you hold about me.")).toBeVisible();
    await expect(page.getByText("Never asked for")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "admin-ticket");
    await page.getByLabel(/^Emailed to /).fill("Your file is on its way.");
    await page.getByRole("button", { name: "Send the answer" }).click();
    await expect
      .poll(() => answered)
      .toMatchObject({ message: "Your file is on its way.", status: "waiting" });
    await page.getByRole("button", { name: "Start an export" }).click();
    await page.getByLabel("Reason").fill("privacy request #1042");
    await page.getByRole("button", { name: "Start the export" }).click();
    await expect.poll(() => exported).toBe(true);
  });

  test("a notice goes to the people chosen, after a reason", async ({ page }, info) => {
    let sent: Record<string, unknown> | null = null;
    await open(page, "/admin/notices/", {
      "/admin/v1/notices": (call, request) => {
        sent = request.postDataJSON() as Record<string, unknown>;
        return { json: { noticeId: "n", sent: 1, withoutAddress: [] } };
      },
    });
    await page.getByLabel("Account ids, one per line").fill(data.me().id);
    await page.getByLabel("Subject").fill("About your account");
    await page.getByLabel("The notice").fill("Here is what happened.");
    await expectAccessible(page);
    await capture(page, info, "admin-notices");
    await page.getByRole("button", { name: "Email the notice" }).click();
    await page.getByLabel("Reason").fill("incident 2026-10 notice");
    await page.getByRole("button", { name: "Send it" }).click();
    await expect(page.getByText("Emailed to 1.")).toBeVisible();
    expect(sent).toMatchObject({ userIds: [data.me().id], subject: "About your account" });
  });
});

test.describe("renters' own images", () => {
  test("lists every submission with what its check found, and blocks one after a reason", async ({
    page,
  }, info) => {
    let blocked: Record<string, unknown> | null = null;
    await open(page, "/admin/images/", {
      [`/admin/v1/images/${images.readyImage.id}/block`]: (_call, request) => {
        blocked = request.postDataJSON() as Record<string, unknown>;
        return { json: {}, status: 204 };
      },
    });
    await expect(page.getByRole("heading", { level: 1, name: "Images" })).toBeVisible();
    const table = page.getByRole("table", { name: "Images" });
    await expect(table).toContainText("ghcr.io/asha/train:v3");
    await expect(table).toContainText("private registry");
    await expect(table).toContainText("blocklisted");
    await expectAccessible(page);
    await capture(page, info, "admin-images");
    await table.getByRole("button", { name: new RegExp(`Block ${images.readyImage.id}`) }).click();
    await page.getByLabel("Reason").fill("a renter reported it as a miner");
    await page.getByRole("button", { name: "Block", exact: true }).last().click();
    await expect.poll(() => blocked).toMatchObject({ reason: "a renter reported it as a miner" });
  });

  test("says when no image has been submitted", async ({ page }) => {
    await open(page, "/admin/images/", { "/admin/v1/images": { json: { items: [], nextCursor: null } } });
    await expect(page.getByText("Nothing here")).toBeVisible();
    await expectAccessible(page);
  });
});
