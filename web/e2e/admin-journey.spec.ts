import { expect, test, type Page } from "@playwright/test";
import { createHmac } from "node:crypto";
import { writeFileSync } from "node:fs";
import { signIn } from "./support";

/**
 * The admin console against the real stack (ADR 0027), run by scripts/e2e/agent-online.sh, which makes
 * E2E_ADMIN_USER an admin with `grant-admin`, leaves E2E_PENDING_HOST enrolled, connected and listed but not
 * activated, and books E2E_RUNNING_SESSION for E2E_WEB_USER, running since E2E_RUNNING_SINCE (epoch milliseconds).
 * One page throughout: the admin session lives only in its memory, as it does for a person. Once the session is
 * force-stopped, its renter rates it and reports a problem in a browser of their own, the admin refunds part of the
 * report, the renter's credit and history show the refund, and the host's owner (E2E_HOST_OWNER) finds a line for it
 * in their earnings (ADR 0028). The script then reads each action's audit row, and the refund's ledger entries.
 */
const adminUser = process.env.E2E_ADMIN_USER;
const pendingHost = process.env.E2E_PENDING_HOST;
const runningSession = process.env.E2E_RUNNING_SESSION;
const webUser = process.env.E2E_WEB_USER;
const hostOwner = process.env.E2E_HOST_OWNER;
const runningSince = Number(process.env.E2E_RUNNING_SINCE);

/** RFC 6238: the authenticator's code for the base32 secret now, from a fresh 30-second step. */
async function freshCode(secret: string, page: Page): Promise<string> {
  // A code is accepted once per step, and one typed near a step's end may land in the next: start a step afresh.
  const into = (Date.now() / 1000) % 30;
  if (into > 24) {
    await page.waitForTimeout((30 - into + 0.5) * 1000);
  }
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) {
    bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[19]! & 0x0f;
  const binary = (hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return binary.toString().padStart(6, "0");
}

async function go(page: Page, screen: string) {
  await page.getByRole("navigation", { name: "Admin" }).getByRole("link", { name: screen }).click();
  await expect(page.getByRole("heading", { level: 1, name: screen })).toBeVisible();
}

async function confirmIn(page: Page, dialog: string, button: string, reason: string) {
  const sheet = page.getByRole("dialog", { name: dialog });
  await sheet.getByLabel("Reason").fill(reason);
  await sheet.getByRole("button", { name: button }).click();
  await expect(sheet).toBeHidden({ timeout: 30_000 });
}

/** An amount shown as ₹1,234.56, in whole paise, read without going through floating point. */
function paise(shown: string): number {
  const [rupees = "0", fraction = "00"] = shown.replace(/[^\d.]/g, "").split(".");
  return Number(rupees) * 100 + Number(fraction.padEnd(2, "0").slice(0, 2));
}

/** Paise as the app shows them: 12 is ₹0.12, never through floating point. */
const shown = (value: number) => `₹${Math.floor(value / 100)}.${String(value % 100).padStart(2, "0")}`;

/** What the description list on the page says beside `label`, as paise. */
async function figure(page: Page, label: string): Promise<number> {
  const value = page.locator("dt", { hasText: label }).locator("xpath=following-sibling::dd").first();
  return paise((await value.textContent()) ?? "");
}

test("an admin enrols a second factor, then activates a host, grants credit and stops a session, and refunds a report", async ({
  page,
  browser,
  baseURL,
}) => {
  test.skip(
    !adminUser || !pendingHost || !runningSession || !webUser || !hostOwner || !runningSince,
    "needs scripts/e2e/agent-online.sh",
  );
  const report: Record<string, unknown> = {};

  await test.step("enrol TOTP and reach the dashboard", async () => {
    await signIn(page, adminUser!, "/admin/");
    await expect(page.getByRole("heading", { level: 1, name: "Set up your authenticator" })).toBeVisible();
    await expect(page.getByRole("img", { name: "QR code of your authenticator secret" })).toBeVisible();
    const secret = (await page.getByText(/^[A-Z2-7]{32}$/).textContent())!;
    await page.getByLabel("Code from your authenticator app").fill(await freshCode(secret, page));
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page.getByRole("list", { name: "Recovery codes" }).getByRole("listitem")).toHaveCount(10);
    await page.getByRole("button", { name: "I've saved them" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
    const hostsOnline = page
      .getByRole("list", { name: "Figures" })
      .getByRole("listitem")
      .filter({ hasText: "Hosts online" });
    const online = Number((await hostsOnline.textContent())!.match(/\d+/)![0]);
    expect(online).toBeGreaterThan(0);
    report.dashboard = { hostsOnline: online };
  });

  await test.step("activate a pending host", async () => {
    await go(page, "Hosts");
    await page.getByLabel("Name, host id or owner id").fill(pendingHost!);
    await page.getByRole("button", { name: "Search" }).click();
    await page.getByRole("link", { name: /^Open/ }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Host" })).toBeVisible();
    await expect(page.getByText("pending").first()).toBeVisible();
    await page.getByRole("button", { name: "Activate" }).click();
    const sheet = page.getByRole("dialog", { name: /^Activate / });
    await sheet.getByLabel("Reason").fill("ok");
    await sheet.getByRole("button", { name: "Activate" }).click();
    await expect(sheet.getByText(/at least 5 characters/)).toBeVisible();
    await confirmIn(
      page,
      (await sheet.getByRole("heading").textContent())!,
      "Activate",
      "passed the end-to-end checks",
    );
    await expect(page.getByRole("table", { name: "Status history" })).toContainText("pending → active");
    report.activated = pendingHost;
  });

  await test.step("grant credit and see it in the audit log", async () => {
    await go(page, "Users");
    await page.getByLabel("Email, name or id").fill(`${webUser}@example.com`);
    await page.getByRole("button", { name: "Search" }).click();
    await page.getByRole("link", { name: /^Open/ }).click();
    await page.getByRole("button", { name: "Grant credit" }).click();
    const sheet = page.getByRole("dialog", { name: /^Grant credit to / });
    await sheet.getByLabel("Amount in ₹").fill("1.25");
    await expect(sheet).toContainText("₹1.25 of free credit is added to their wallet.");
    await confirmIn(
      page,
      (await sheet.getByRole("heading").textContent())!,
      "Grant credit",
      "end-to-end admin grant",
    );
    await expect(page.getByRole("table", { name: "Credit grants" })).toContainText("end-to-end admin grant");
    await go(page, "Audit log");
    await page.getByLabel("Action").fill("credit.");
    await page.getByRole("button", { name: "Search" }).click();
    const row = page
      .getByRole("table", { name: "Audit log" })
      .getByRole("row")
      .filter({ hasText: "end-to-end admin grant" });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("credit.grant");
    await expect(row).toContainText(`${adminUser}@example.com`);
    await expect(row).toContainText('"amountPaise":125');
    report.granted = { user: `${webUser}@example.com`, paise: 125 };
  });

  await test.step("force-stop a running session", async () => {
    await go(page, "Sessions");
    await page.getByLabel("Session id").fill(runningSession!);
    await page.getByRole("button", { name: "Search" }).click();
    await page.getByRole("link", { name: /^Open/ }).click();
    await expect(page.getByText("RUNNING").first()).toBeVisible({ timeout: 30_000 });
    // Let it run past a minute first: a session is rated and its minutes are charged only once it has.
    const toWait = runningSince + 75_000 - Date.now();
    if (toWait > 0) {
      await page.waitForTimeout(toWait);
    }
    await page.getByRole("button", { name: "Force stop" }).click();
    await confirmIn(page, "Force-stop this session?", "Force stop", "end-to-end force stop");
    await expect(page.getByText("COMPLETED").first()).toBeVisible({ timeout: 120_000 });
    await expect(page.getByRole("region", { name: "Timeline" })).toContainText("operator_stopped");
    report.stopped = runningSession;
  });

  const renterPage = await (
    await browser.newContext({ baseURL, viewport: { width: 1280, height: 800 } })
  ).newPage();
  const ownerPage = await (
    await browser.newContext({ baseURL, viewport: { width: 1280, height: 800 } })
  ).newPage();
  let refund = 0;

  await test.step("the renter rates the stopped session with one tap, then reports a problem with it", async () => {
    await signIn(renterPage, webUser!, `/session/?id=${runningSession}`);
    await expect(renterPage.getByRole("heading", { name: "Done" })).toBeVisible({ timeout: 60_000 });
    // The control plane notes the session's end a moment after it changes; the app asks again until it has.
    await expect(renterPage.getByRole("heading", { name: "How did it go?" })).toBeVisible();
    await expect(renterPage.getByRole("button", { name: "3 stars" })).toBeEnabled({ timeout: 30_000 });
    await renterPage.getByRole("button", { name: "3 stars" }).click();
    await expect(renterPage.getByRole("heading", { name: "Thanks for rating" })).toBeVisible();
    await expect(renterPage.getByRole("button", { name: "3 stars" })).toHaveAttribute("aria-pressed", "true");
    await renterPage.getByLabel("Comment (optional)").fill("end-to-end rating comment");
    await renterPage.getByRole("button", { name: "Save comment" }).click();
    await expect(renterPage.getByRole("button", { name: "Save comment" })).toBeDisabled();
    // A reload shows what the server kept.
    await renterPage.reload();
    await expect(renterPage.getByRole("group", { name: "You gave 3 out of 5 stars" })).toBeVisible();

    await renterPage.getByRole("button", { name: /Report a problem/ }).click();
    const sheet = renterPage.getByRole("dialog", { name: "Report a problem" });
    await sheet.getByRole("button", { name: /The charge looks wrong/ }).click();
    await sheet.getByLabel("Tell us more (optional)").fill("end-to-end report");
    await sheet.getByRole("button", { name: "Send report" }).click();
    await expect(renterPage.getByRole("dialog", { name: "Report sent" })).toContainText(
      "We'll reply within 2 days.",
    );
    await renterPage.getByRole("button", { name: "Done" }).click();
    await expect(renterPage.getByRole("link", { name: /Your report is open/ })).toBeVisible();
    report.rated = { stars: 3 };
  });

  await test.step("the admin finds the report and refunds part of it, the commission first", async () => {
    await go(page, "Reports");
    const row = page
      .getByRole("table", { name: "Reports" })
      .getByRole("row")
      .filter({ hasText: runningSession! });
    await expect(row).toContainText("Open");
    await row.getByRole("link", { name: /^Open/ }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Report" })).toBeVisible();
    await expect(page.getByText("end-to-end report")).toBeVisible();
    await expect(page.getByText("end-to-end rating comment")).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Timeline" })).toBeVisible();
    await expect(page.getByRole("table", { name: "Settlements" })).toBeVisible();
    const paid = await figure(page, "The renter paid");
    const commission = await figure(page, "The platform kept");
    // One more than the platform's commission: it gives all it kept, and the host one paisa.
    refund = commission + 1;
    expect(paid).toBeGreaterThan(refund);
    await page.getByRole("button", { name: "Refund", exact: true }).click();
    const sheet = page.getByRole("dialog", { name: "Refund this report?" });
    // More than the session cost is refused in the sheet, and nothing is sent.
    await sheet
      .getByLabel("Amount in ₹")
      .fill(`${Math.floor((paid + 1) / 100)}.${String((paid + 1) % 100).padStart(2, "0")}`);
    await sheet.getByLabel("Reason").fill("end-to-end refund");
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toContainText("can't be more than the session cost");
    await sheet
      .getByLabel("Amount in ₹")
      .fill(`${Math.floor(refund / 100)}.${String(refund % 100).padStart(2, "0")}`);
    await sheet.getByRole("button", { name: "Refund", exact: true }).click();
    await expect(sheet).toBeHidden({ timeout: 30_000 });
    await expect(page.getByRole("heading", { level: 2, name: "How it was decided" })).toBeVisible();
    expect(await figure(page, "From the commission")).toBe(commission);
    expect(await figure(page, "From the hosts")).toBe(1);
    expect(await figure(page, "The renter paid")).toBe(paid - refund);
    report.refund = { paid, commission, refund, fromCommission: commission, fromHosts: 1 };
  });

  await test.step("the renter's session, credit and history show the refund", async () => {
    await renterPage.reload();
    const row = renterPage.getByRole("link", { name: /Report resolved/ });
    await expect(row).toContainText(`${shown(refund)} refunded`, { timeout: 30_000 });
    await expect(row).toContainText("end-to-end refund");
    await renterPage.goto("/credit/");
    const entry = renterPage.getByRole("link", { name: /Refund after your report/ });
    await expect(entry).toContainText(`+${shown(refund)}`);
    await expect(entry).toHaveAttribute("href", `/session/?id=${runningSession}`);
    await renterPage.goto("/account/reports/");
    await expect(
      renterPage.getByRole("listitem").filter({ hasText: "The charge looks wrong" }),
    ).toContainText(`Report resolved: ${shown(refund)} refunded`);
  });

  await test.step("the host's owner finds the refund as a line of their earnings, and nothing about the renter", async () => {
    await signIn(ownerPage, hostOwner!, "/hosting/earnings/");
    const line = ownerPage.getByRole("listitem").filter({ hasText: "Refunded to renter" }).first();
    await expect(line).toContainText("Taken back −₹0.01");
    await expect(ownerPage.locator("main")).not.toContainText(webUser!);
    await expect(ownerPage.locator("main")).not.toContainText("end-to-end");
    report.hostLine = { amountPaise: -1 };
  });

  // The privacy journey's message (ADR 0037): open in Tickets by its number, answered, emailed to the person.
  const ticketNumber = process.env.E2E_TICKET_NUMBER;
  if (ticketNumber) {
    await test.step(`ticket #${ticketNumber} is answered from Tickets`, async () => {
      await go(page, "Tickets");
      await page.getByRole("link", { name: `#${ticketNumber}` }).click();
      await expect(page.getByText("Please tell me what you hold about me.")).toBeVisible();
      await expect(page.getByText("Never asked for").or(page.getByText("cancelled"))).toBeVisible();
      await page
        .getByLabel(/^Emailed to /)
        .fill("We've had a look at your request: your file is in Account, Your data.");
      await page.getByRole("button", { name: "Send the answer" }).click();
      await expect(page.getByText("We wrote", { exact: false }).first()).toBeVisible();
    });
  }

  // Money in (ADR 0039): the payments journey's refund request approved, its chargeback's evidence read, and today's
  // payments compared with Razorpay.
  if (process.env.E2E_REFUND_NOTE) {
    await test.step("a refund of bought credit is approved and goes back to where it was paid from", async () => {
      await go(page, "Refunds");
      const row = page.getByRole("row").filter({ hasText: process.env.E2E_REFUND_NOTE! });
      await row.getByRole("button", { name: /^Approve/ }).click();
      const sheet = page.getByRole("dialog", { name: "Refund this bought credit?" });
      await expect(sheet.getByLabel("Amount in ₹")).toHaveValue("200");
      await sheet.getByLabel("Reason").fill("end-to-end refund approval");
      await sheet.getByRole("button", { name: "Refund", exact: true }).click();
      await expect(sheet).toBeHidden();
    });
  }
  if (process.env.E2E_CHARGEBACK) {
    await test.step("a chargeback shows its evidence and the date it's due", async () => {
      await go(page, "Chargebacks");
      await page.getByRole("link", { name: `Open chargeback ${process.env.E2E_CHARGEBACK}` }).click();
      await expect(page.getByRole("heading", { level: 1, name: "Chargeback" })).toBeVisible();
      await expect(page.getByText("Submit the evidence in Razorpay's dashboard")).toBeVisible();
      await expect(page.getByRole("heading", { level: 2, name: "The payment" })).toBeVisible();
    });
    await test.step("today's payments are compared with Razorpay, and nothing disagrees", async () => {
      await go(page, "Payments");
      await page.getByRole("button", { name: "Run a day now" }).click();
      await confirmIn(page, "Compare a day with Razorpay now?", "Run it", "end-to-end reconciliation");
      // The only run on this stack: the nightly one runs at 03:15.
      await expect(page.getByRole("table", { name: "Runs" }).getByRole("row")).toHaveCount(2, {
        timeout: 30_000,
      });
      await expect(page.getByText("Nothing open")).toBeVisible();
    });
  }

  if (process.env.E2E_ADMIN_REPORT) {
    writeFileSync(process.env.E2E_ADMIN_REPORT, JSON.stringify(report, null, 2));
  }
});
