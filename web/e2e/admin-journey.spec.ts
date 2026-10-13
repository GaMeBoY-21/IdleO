import { expect, test, type Page } from "@playwright/test";
import { createHmac } from "node:crypto";
import { writeFileSync } from "node:fs";
import { signIn } from "./support";

/**
 * The admin console against the real stack (ADR 0027), run by scripts/e2e/agent-online.sh, which makes
 * E2E_ADMIN_USER an admin with `grant-admin`, leaves E2E_PENDING_HOST enrolled, connected and listed but not
 * activated, and books E2E_RUNNING_SESSION for E2E_WEB_USER. One page throughout: the admin session lives only in its
 * memory, as it does for a person. The script then reads each action's audit row from the database.
 */
const adminUser = process.env.E2E_ADMIN_USER;
const pendingHost = process.env.E2E_PENDING_HOST;
const runningSession = process.env.E2E_RUNNING_SESSION;
const webUser = process.env.E2E_WEB_USER;

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

test("an admin enrols a second factor, then activates a host, grants credit and stops a session", async ({
  page,
}) => {
  test.skip(!adminUser || !pendingHost || !runningSession || !webUser, "needs scripts/e2e/agent-online.sh");
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
    await page.getByRole("button", { name: "Force stop" }).click();
    await confirmIn(page, "Force-stop this session?", "Force stop", "end-to-end force stop");
    await expect(page.getByText("COMPLETED").first()).toBeVisible({ timeout: 120_000 });
    await expect(page.getByRole("region", { name: "Timeline" })).toContainText("operator_stopped");
    report.stopped = runningSession;
  });

  if (process.env.E2E_ADMIN_REPORT) {
    writeFileSync(process.env.E2E_ADMIN_REPORT, JSON.stringify(report, null, 2));
  }
});
