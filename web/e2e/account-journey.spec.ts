import { expect, test, type Page } from "@playwright/test";
import { expectAccessible, watchConsole } from "./support";

/**
 * Access by invitation and the account, on the real stack (ADR 0024, scripts/e2e/agent-online.sh): a new person signs in
 * and is asked for an invite code, a code that doesn't work is said not to, the one the script made lets them in, a
 * code is used once, and Account saves a name and a choice that are still there after a reload, shows the locked kinds
 * locked, and signs out. The two new people are invented @example.com users, signed in through the local sign-in
 * server; the code comes from the script (E2E_INVITE_CODE), made with the operator's create-invites.
 */
const base = process.env.E2E_WEB_USER ?? "";
const code = process.env.E2E_INVITE_CODE ?? "";

test.describe.configure({ mode: "serial" });

async function firstSignIn(page: Page, who: string) {
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill(`${who}@example.com`);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL("**/invite/**");
  await expect(page.getByRole("heading", { name: "Enter your invite code" })).toBeVisible();
}

test("a new account enters its invite code, edits its account and signs out", async ({ page }) => {
  expect(base, "E2E_WEB_USER").not.toBe("");
  expect(code, "E2E_INVITE_CODE").toMatch(/^IDLE-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  const finish = watchConsole(page);
  const who = `${base}-invite`;

  // First sign-in: the invite step, before anything else.
  await firstSignIn(page, who);
  await expectAccessible(page);
  // Nothing else is open to an account without a code, even by going straight to it.
  await page.goto("/book/");
  await page.waitForURL("**/invite/**");

  // A code that isn't one is said not to be; the person types, and it's shown in groups.
  await page.getByLabel("Invite code").fill("idlezzzzzzzz");
  await expect(page.getByLabel("Invite code")).toHaveValue("IDLE-ZZZZ-ZZZZ");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("That code isn't one we can accept.")).toBeVisible();

  // The real one, in lower case without dashes, lets them in: the welcome of a new account, then Home.
  await page.getByLabel("Invite code").fill(code.replaceAll("-", "").toLowerCase());
  await page.getByRole("button", { name: "Continue" }).click();
  await page.waitForURL("**/welcome/");
  await page.getByRole("button", { name: "Go to your home" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();

  // Account, from the avatar menu.
  await page.getByRole("button", { name: "Your account and settings" }).click();
  await page.getByRole("menuitem", { name: "Account" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();
  await expect(page.getByText(`${who}@example.com`, { exact: true })).toBeVisible();
  await expect(page.getByText("An email link", { exact: true })).toBeVisible();
  await expect(page.getByRole("switch")).toHaveCount(26);
  for (const title of ["Stopped for credit or cap", "Host lost", "Machine offline"]) {
    await expect(page.getByRole("switch", { name: `${title}: email` })).toBeDisabled();
    await expect(page.getByRole("switch", { name: `${title}: in the app` })).toBeChecked();
  }
  await expectAccessible(page);

  // A name, saved and still there after a reload.
  await page.getByRole("button", { name: "Edit your name" }).click();
  await page.getByLabel("Name").fill("  Asha Rao ");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "Edit your name" })).toBeVisible();
  await expect(page.getByText("Asha Rao", { exact: true })).toBeVisible();

  // A choice, saved once the server has it, and still there after a reload.
  const lowCredit = page.getByRole("switch", { name: "Low credit: email" });
  await lowCredit.click();
  await expect(page.getByText("Saved.")).toBeVisible();
  await expect(lowCredit).not.toBeChecked();
  await page.reload();
  await expect(page.getByText("Asha Rao", { exact: true })).toBeVisible();
  await expect(page.getByRole("switch", { name: "Low credit: email" })).not.toBeChecked();
  await expect(page.getByRole("switch", { name: "Low credit: in the app" })).toBeChecked();

  // Signing out, then in again: an active account goes straight to Home, with no code to enter.
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL("**/sign-in/**");
  await page.getByLabel("Email", { exact: true }).fill(`${who}@example.com`);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"));
  expect(new URL(page.url()).pathname).not.toBe("/invite/");
  finish();
});

test("a code can be used once", async ({ page }) => {
  expect(code).not.toBe("");
  const finish = watchConsole(page);
  // The first test used the script's code; a second new person is told it isn't one that works.
  await firstSignIn(page, `${base}-invite-two`);
  await page.getByLabel("Invite code").fill(code);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("That code isn't one we can accept.")).toBeVisible();
  await expect(page).toHaveURL(/\/invite\//);
  finish();
});
