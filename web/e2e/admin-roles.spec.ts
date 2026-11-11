import { createHmac } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { expect, test, type Browser, type Page } from "@playwright/test";
import { API, signIn, typeToConfirm } from "./support";
import { ADMIN_URL, APP_URL } from "./sites";

// The console is the admin site's alone (ADR 0058): every address here is on it.
test.use({ baseURL: ADMIN_URL });

/**
 * A realistic day for each role, against the real stack (ADR 0057), run by scripts/e2e/agent-online.sh in four parts, each
 * on a stack of its own (`--grep` picks them): Support and the Auditor; Trust & Safety, then six months on; Operations;
 * and, once money has moved, Finance's reconciliation, batch and CA pack at the start of next month and the Owner's
 * close of the month the money moved in. Within a part they run in order, as they share its world and its clock.
 */
const owner = process.env.E2E_ROLE_OWNER;
const approver = process.env.E2E_ROLE_APPROVER;
const operations = process.env.E2E_ROLE_OPERATIONS;
const support = process.env.E2E_ROLE_SUPPORT;
const trust = process.env.E2E_ROLE_TRUST;
const finance = process.env.E2E_ROLE_FINANCE;
const auditor = process.env.E2E_ROLE_AUDITOR;
const person = process.env.E2E_ROLE_PERSON ?? "";
const personId = process.env.E2E_ROLE_PERSON_ID ?? "";
const personToken = process.env.E2E_ROLE_PERSON_TOKEN ?? "";
const other = process.env.E2E_ROLE_OTHER ?? "";
const ticket = process.env.E2E_ROLE_TICKET ?? "";
const lateTicket = process.env.E2E_ROLE_LATE_TICKET ?? "";
const macro = process.env.E2E_ROLE_MACRO ?? "";
const month = process.env.E2E_ROLE_MONTH ?? "";
const release = process.env.E2E_ROLE_RELEASE ?? "";
const clockFile = process.env.E2E_CLOCK_FILE ?? "";
/** Where each admin's authenticator secret is kept for the rest of a run (gitignored private/e2e/, a test stack's). */
const secrets = process.env.E2E_ROLE_SECRETS ?? "";

test.skip(!owner, "run by scripts/e2e/agent-online.sh");
test.describe.configure({ mode: "serial" });

function offset(): number {
  try {
    return Number(readFileSync(clockFile, "utf8").trim()) || 0;
  } catch {
    return 0;
  }
}

/** Moves the whole stack's clock on to this time, and waits until the control plane has read it. */
async function clockTo(page: Page, when: Date): Promise<void> {
  writeFileSync(clockFile, String(Math.floor((when.getTime() - Date.now()) / 1000)));
  await page.waitForTimeout(1_000);
}

/** RFC 6238 on the stack's clock: the authenticator's code for this secret now. */
function codeNow(secret: string): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) {
    bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor((Date.now() / 1000 + offset()) / 30)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const at = hash[19]! & 0x0f;
  return ((hash.readUInt32BE(at) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

/** A code from a fresh 30-second step: one is accepted once per step, and one typed at a step's end may land in the next. */
async function freshCode(page: Page, secret: string): Promise<string> {
  const into = (Date.now() / 1000 + offset()) % 30;
  if (into > 22) {
    await page.waitForTimeout((30 - into + 0.5) * 1000);
  }
  return codeNow(secret);
}

/** An admin's own browser, and where the clock stood when they last signed in. */
type Admin = { page: Page; secret: string; signedInAt: number };
const admins = new Map<string, Admin>();

/** This admin's own browser: enrolled at their first visit, signed in again if their session has run out. */
async function as(browser: Browser, user: string): Promise<Admin> {
  const known = admins.get(user);
  if (known) {
    return known;
  }
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await signIn(page, user, "/admin/");
  const enrolling = page.getByRole("heading", { level: 1, name: "Set up your authenticator" });
  const signingIn = page.getByRole("heading", { level: 1, name: "Admin sign-in" });
  await expect(enrolling.or(signingIn)).toBeVisible();
  let secret: string;
  if (await enrolling.isVisible()) {
    secret = (await page.getByText(/^[A-Z2-7]{32}$/).textContent())!;
    await page.getByLabel("Code from your authenticator app").fill(await freshCode(page, secret));
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(page.getByRole("list", { name: "Recovery codes" }).getByRole("listitem")).toHaveCount(10);
    await page.getByRole("button", { name: "I've saved them" }).click();
    writeFileSync(`${secrets}/${user}`, secret);
  } else {
    // Enrolled by an earlier part of the same run, on the same stack: their authenticator is the file it kept.
    secret = readFileSync(`${secrets}/${user}`, "utf8").trim();
    await page.waitForTimeout(31_000);
    await page.getByLabel("Code from your authenticator app").fill(await freshCode(page, secret));
    await page.getByRole("button", { name: "Continue" }).click();
  }
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
  // Whenever the console asks for a recent factor, they give one, and what they were doing goes on by itself.
  const asked = page.getByRole("dialog", { name: "Confirm it's you" });
  await page.addLocatorHandler(asked, async () => {
    await page.waitForTimeout(31_000);
    await asked.getByLabel("Code from your authenticator app").fill(await freshCode(page, secret));
    await asked.getByRole("button", { name: "Confirm" }).click();
    await expect(asked).toBeHidden({ timeout: 30_000 });
  });
  const admin = { page, secret, signedInAt: offset() };
  admins.set(user, admin);
  return admin;
}

/**
 * Signs in the second admin before anything is asked of them: only an admin whose second factor is confirmed counts as
 * one who can approve, and a request made while nobody else can goes solo, to wait out its cooling instead.
 */
async function approverReady(browser: Browser): Promise<void> {
  await as(browser, approver!);
}

/** Their session has run out with the clock: the page loads again and asks them for a code, as it does a person. */
async function again(admin: Admin): Promise<void> {
  const { page, secret } = admin;
  await page.goto("/admin/");
  await expect(page.getByRole("heading", { level: 1, name: "Admin sign-in" })).toBeVisible();
  await page.waitForTimeout(31_000);
  await page.getByLabel("Code from your authenticator app").fill(await freshCode(page, secret));
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
  admin.signedInAt = offset();
}

async function go(page: Page, screen: string): Promise<void> {
  await page
    .getByRole("navigation", { name: "Admin" })
    // The Inbox and Approvals say how many wait, after their names.
    .getByRole("link", { name: new RegExp(`^${screen}( \\d.*)?$`) })
    .click();
  await expect(page.getByRole("heading", { level: 1, name: screen })).toBeVisible();
}

async function confirm(page: Page, button: string, reason: string): Promise<void> {
  const sheet = page.getByRole("dialog").filter({ has: page.getByRole("button", { name: button }) });
  await sheet.getByLabel("Reason", { exact: true }).fill(reason);
  if ((await sheet.getByLabel(/to confirm$/).count()) > 0) {
    await typeToConfirm(sheet);
  }
  await sheet.getByRole("button", { name: button }).click();
  // What goes to another admin stays open to say so, until it's dismissed.
  const done = page.getByRole("dialog").getByRole("button", { name: "Done" });
  await expect(sheet.or(done).first())
    .toBeHidden({ timeout: 5_000 })
    .catch(async () => {
      if (await done.isVisible()) {
        await done.click();
      }
    });
  await expect(page.getByRole("dialog")).toHaveCount(0, { timeout: 60_000 });
}

/** A second admin approves what must not rest on one person, from their own screen: the request asked for with `reason`. */
async function approved(browser: Browser, reason: string): Promise<void> {
  const admin = await as(browser, approver!);
  // A jump of the clock has ended the session they had.
  if (admin.signedInAt !== offset()) {
    await again(admin);
  }
  const { page } = admin;
  // From another screen, so the list is read afresh: a second request finds them on Approvals already.
  await go(page, "Control room");
  await go(page, "Approvals");
  const request = page
    .getByRole("table", { name: "Approval requests" })
    .getByRole("row")
    .filter({ hasText: reason })
    .first();
  await expect(request).toBeVisible();
  await request.getByRole("button", { name: /^Approve/ }).click();
  await confirm(page, "Approve and run it", "I checked this with the person who asked");
}

/** Their person's page, found by the search box. */
async function openPerson(page: Page, email: string): Promise<void> {
  await go(page, "Users");
  await page.getByLabel("Email, name or id").fill(email);
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByRole("link", { name: /^Open/ }).click();
}

test("Support: the inbox, a macro, a target run out, and a reveal with a reason", async ({ browser }) => {
  test.setTimeout(480_000);
  const admin = await as(browser, support!);
  const { page } = admin;
  await page.keyboard.press("g");
  await page.keyboard.press("i");
  await expect(page.getByRole("heading", { level: 1, name: "Inbox" })).toBeVisible();
  await page.getByLabel("Kind of work").selectOption("ticket");
  const row = page.getByRole("row").filter({ hasText: ticket });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole("button", { name: /^Claim/ }).click();
  await expect(row).toContainText("is on it");
  await row.getByRole("button", { name: /^Macro/ }).click();
  const sheet = page.getByRole("dialog", { name: "Apply a macro?" });
  await sheet.getByRole("combobox").first().selectOption({ label: macro });
  await confirm(page, "Apply it", "It's the answer they need");
  await expect(page.getByRole("row").filter({ hasText: ticket })).toHaveCount(0, { timeout: 30_000 });

  // A reveal: nothing without a reason, and then the whole address, logged.
  await openPerson(page, `${person}@example.com`);
  await page.getByRole("button", { name: "Reveal" }).first().click();
  await confirm(page, "Show it", "They asked us to confirm the address on file");
  await expect(page.getByText(`${person}@example.com`).first()).toBeVisible({ timeout: 60_000 });

  // The clock moves on three days: the ticket nobody answered is past its target, which the inbox says in words.
  await clockTo(page, new Date(Date.now() + offset() * 1000 + 3 * 86_400_000));
  await again(admin);
  await go(page, "Inbox");
  await page.getByLabel("Kind of work").selectOption("ticket");
  const late = page.getByRole("row").filter({ hasText: lateTicket });
  await expect(late).toContainText("Past its target", { timeout: 30_000 });
});

test("Trust & Safety: linked accounts, the ladder, and an appeal another admin decides", async ({
  browser,
}) => {
  test.setTimeout(480_000);
  await approverReady(browser);
  // Two people on one browser and address, one after the other: what links them.
  for (const who of [person, other]) {
    const context = await browser.newContext();
    await signIn(await context.newPage(), who, "/", APP_URL);
    await context.close();
  }

  const { page } = await as(browser, trust!);
  await openPerson(page, personId);
  await page.getByRole("tab", { name: "Linked accounts" }).click();
  await expect(
    page.getByRole("list", { name: "Linked accounts" }).getByText("same browser").first(),
  ).toBeVisible({
    timeout: 30_000,
  });

  await page.getByRole("tab", { name: "Enforcement" }).click();
  // Nobody has taken a step yet: the panel says so, and counts nothing.
  await expect(page.getByText("Nothing yet")).toBeVisible();
  await page.getByRole("button", { name: "Take a step" }).click();
  await page.getByLabel("Step", { exact: true }).selectOption("warn");
  await confirm(page, "Take it", "A first warning about how they use the service");
  await expect(page.getByText(/Strikes that still count: 1 as a renter/)).toBeVisible({ timeout: 30_000 });

  // They appeal, from their side.
  const headers = { Authorization: `Bearer ${personToken}`, "Content-Type": "application/json" };
  const theirs = (await (await page.request.get(`${API}/v1/me/enforcements`, { headers })).json()) as {
    enforcements: { id: string }[];
  };
  const appealed = await page.request.post(`${API}/v1/me/enforcements/${theirs.enforcements[0]!.id}/appeal`, {
    headers: { ...headers, "Idempotency-Key": `"${crypto.randomUUID()}"` },
    data: { body: "I think this was a misunderstanding of what I was running." },
  });
  expect(appealed.ok(), await appealed.text()).toBe(true);

  // Who enforced it can't decide it while another admin can.
  await go(page, "Appeals");
  const appeal = page
    .getByRole("row")
    .filter({ hasText: personId.slice(-8) })
    .first();
  await appeal.getByRole("link", { name: /^Open/ }).click();
  await page.getByRole("button", { name: "Keep the decision" }).click();
  const sheet = page.getByRole("dialog");
  await sheet.getByLabel("Reason").fill("I read it again and it stands");
  await sheet.locator('button[type="submit"]').click();
  await expect(
    sheet
      .getByRole("alert")
      .or(sheet.getByText(/another admin|someone else|who enforced/i))
      .first(),
  ).toBeVisible({
    timeout: 60_000,
  });
  await sheet.getByRole("button", { name: "Cancel" }).click();

  const second = await as(browser, approver!);
  await go(second.page, "Appeals");
  await second.page
    .getByRole("row")
    .filter({ hasText: personId.slice(-8) })
    .first()
    .getByRole("link", { name: /^Open/ })
    .click();
  await second.page.getByRole("button", { name: "Keep the decision" }).click();
  const decide = second.page.getByRole("dialog");
  await decide.getByLabel("Reason").fill("I read what they said and the warning stands");
  await decide.locator('button[type="submit"]').click();
  await expect(decide).toBeHidden({ timeout: 60_000 });
  await expect(second.page.getByText(/Decided:/)).toBeVisible({ timeout: 30_000 });
});

test("Operations: a rollout, widened with approval and paused, and maintenance mode", async ({ browser }) => {
  test.setTimeout(480_000);
  await approverReady(browser);
  const { page } = await as(browser, operations!);
  await go(page, "Fleet");
  const rollout = page.getByRole("table", { name: "Rollouts" }).getByRole("row").filter({ hasText: release });
  await expect(rollout).toBeVisible();
  // Its state: a share like "5%", or "paused".
  const share = rollout.getByRole("cell").nth(1);
  const before = await share.innerText();
  await rollout.getByRole("button", { name: /^(Run again|Widen)/ }).click();
  await confirm(page, "Ask to widen", "The first hosts look healthy, so a wider share");
  await approved(browser, "The first hosts look healthy, so a wider share");
  // Once the approval has run, the screen read again, from another: the widened rollout, at its new version.
  await expect(async () => {
    await go(page, "Control room");
    await go(page, "Fleet");
    await expect(share).not.toHaveText(before, { timeout: 2_000 });
  }).toPass({ timeout: 60_000 });
  await rollout.getByRole("button", { name: /^Pause/ }).click();
  await confirm(page, "Pause now", "Holding it here over the weekend");

  await go(page, "Flags");
  const flag = page
    .getByRole("table", { name: "Every flag, what it stops and its state" })
    .getByRole("row")
    .filter({ hasText: "maintenance" });
  await flag.getByRole("button", { name: "Start maintenance" }).click();
  await confirm(page, "Pause now", "Planned database work");
  const status = (await (await page.request.get(`${API}/v1/public/status`)).json()) as {
    maintenance: boolean;
  };
  expect(status.maintenance).toBe(true);
  await flag.getByRole("button", { name: "End maintenance" }).click();
  await confirm(page, "Ask to run again", "The work is finished");
  await approved(browser, "The work is finished");
  await expect
    .poll(
      async () =>
        ((await (await page.request.get(`${API}/v1/public/status`)).json()) as { maintenance: boolean })
          .maintenance,
      {
        timeout: 60_000,
      },
    )
    .toBe(false);
});

test("Auditor: reads everything they may, changes nothing, and the audit chain holds", async ({
  browser,
}) => {
  test.setTimeout(480_000);
  const { page } = await as(browser, auditor!);
  for (const screen of ["Users", "Sessions", "Ledger", "Months", "Audit log"]) {
    await go(page, screen);
  }
  await page.getByRole("button", { name: "Check the chain now" }).click();
  await expect(page.getByText(/intact/i).first()).toBeVisible({ timeout: 60_000 });
  // Actions they can't take say why, instead of failing.
  await openPerson(page, personId);
  await expect(page.getByRole("button", { name: "Grant credit" })).toBeDisabled();
  await go(page, "Inbox").catch(() => undefined);
});

test("Finance, at the start of next month: reconciliation, a payout batch and the CA pack", async ({
  browser,
}) => {
  test.setTimeout(600_000);
  await approverReady(browser);
  const admin = await as(browser, finance!);
  const { page } = admin;
  const [year, monthNumber] = month.split("-").map(Number) as [number, number];
  // Two days into next month, in India, at noon.
  await clockTo(page, new Date(Date.UTC(year, monthNumber, 2, 6, 30)));
  await again(admin);

  await go(page, "Money");
  await page.getByRole("button", { name: "Run the check now" }).click();
  await confirm(page, "Run the checks", "The month's first reconciliation");
  await expect(page.getByRole("table", { name: "Reconciliation runs" }).getByRole("row").nth(1)).toBeVisible({
    timeout: 60_000,
  });

  await go(page, "Payouts");
  await page.getByRole("button", { name: "Prepare a batch now" }).click();
  await confirm(page, "Prepare it", "This week's payouts");
  await page.getByRole("link", { name: "Review and release" }).first().click();
  await page.getByRole("button", { name: /^Release/ }).click();
  await confirm(page, "Ask to release", "I checked the preview against the ledger");
  await approved(browser, "I checked the preview against the ledger");

  await go(page, "Tax");
  await page.getByLabel("Month (YYYY-MM)").fill(month);
  await page.getByRole("button", { name: "Ask for the month's CA pack" }).click();
  await confirm(page, "Ask for approval", `The CA's files for ${month}`);
  await approved(browser, `The CA's files for ${month}`);
  await go(page, "Approvals");
  // What they asked for, once approved, is among their own requests.
  await page.getByLabel("Show", { exact: true }).selectOption("mine");
  const download = page.waitForEvent("download", { timeout: 60_000 });
  await page
    .getByRole("button", { name: /^Fetch the file/ })
    .first()
    .click();
  expect((await download).suggestedFilename()).toContain(month);
});

test("Owner: an approval, a kill switch, and closing the month the money moved in", async ({ browser }) => {
  test.setTimeout(600_000);
  await approverReady(browser);
  const admin = await as(browser, owner!);
  const { page } = admin;
  await page.keyboard.press("g");
  await page.keyboard.press("c");
  await expect(page.getByRole("heading", { level: 1, name: "Control room" })).toBeVisible();

  // A kill switch: pausing is immediate, running again is another admin's to approve.
  await go(page, "Flags");
  const flag = page
    .getByRole("table", { name: "Every flag, what it stops and its state" })
    .getByRole("row")
    .filter({ hasText: "signups" });
  await flag.getByRole("button", { name: "Pause" }).click();
  await confirm(page, "Pause now", "We're seeing a wave of bad sign-ups");
  await flag.getByRole("button", { name: "Run again" }).click();
  await confirm(page, "Ask to run again", "It has passed");
  await approved(browser, "It has passed");
  await expect(flag.getByRole("button", { name: "Pause" })).toBeVisible({ timeout: 60_000 });

  // The month, once its checklist holds.
  await go(page, "Money");
  await page.getByRole("button", { name: "Run the check now" }).click();
  await confirm(page, "Run the checks", "The last check before closing");
  await go(page, "Months");
  const row = page
    .getByRole("table", { name: "Months and their checklists" })
    .getByRole("row")
    .filter({ hasText: month });
  await expect(row.getByRole("button", { name: "Close", exact: true }), await row.innerText()).toBeVisible({
    timeout: 60_000,
  });
  await row.getByRole("button", { name: "Close", exact: true }).click();
  await confirm(page, "Ask to close", `Everything for ${month} is reconciled`);
  await approved(browser, `Everything for ${month} is reconciled`);
  // Once the approval has run: the screen read again, from another, as a person coming back to it.
  await expect(async () => {
    await go(page, "Control room");
    await go(page, "Months");
    await expect(row.getByRole("button", { name: "Reopen" })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 60_000 });
});

test("Trust & Safety, six months on: the strike has stopped counting", async ({ browser }) => {
  test.setTimeout(240_000);
  const admin = await as(browser, trust!);
  await clockTo(admin.page, new Date(Date.now() + offset() * 1000 + 181 * 86_400_000));
  await again(admin);
  await openPerson(admin.page, personId);
  await admin.page.getByRole("tab", { name: "Enforcement" }).click();
  await expect(admin.page.getByText(/Strikes that still count: 0 as a renter/)).toBeVisible({
    timeout: 30_000,
  });
});
