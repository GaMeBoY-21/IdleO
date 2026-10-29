import { expect, test, type Page } from "@playwright/test";
import { API, expectAccessible, signIn, watchConsole } from "./support";

/**
 * Access by invitation and the account, on the real stack (ADR 0024, scripts/e2e/agent-online.sh): a new person signs in,
 * agrees to the Terms and privacy notice with their age (ADR 0037), is asked for an invite code, a code that doesn't work is said not to, the one the script made lets them in, a
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
  // The Terms and privacy notice first, with the age, before anything else; the server keeps the answer.
  await page.waitForURL("**/agree/**");
  await expect(page.getByRole("heading", { name: "Before you start" })).toBeVisible();
  await page.getByLabel("I agree to the Terms and the Privacy notice").check();
  await page.getByLabel("I'm 18 or older").check();
  await page.getByRole("button", { name: "Agree and continue" }).click();
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

  // Account, from the More menu.
  await page.getByRole("button", { name: "More" }).click();
  await page.getByRole("menuitem", { name: "Account" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();
  await expect(page.getByRole("main").getByText(`${who}@example.com`, { exact: true })).toBeVisible();
  await expect(page.getByText("An email link", { exact: true })).toBeVisible();
  await expect(page.getByRole("switch")).toHaveCount(32);
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
  await expect(page.getByRole("main").getByText("Asha Rao", { exact: true })).toBeVisible();

  // A choice, saved once the server has it, and still there after a reload.
  const lowCredit = page.getByRole("switch", { name: "Low credit: email" });
  await lowCredit.click();
  await expect(page.getByText("Saved.")).toBeVisible();
  await expect(lowCredit).not.toBeChecked();
  await page.reload();
  await expect(page.getByRole("main").getByText("Asha Rao", { exact: true })).toBeVisible();
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

test("a key is made, shown once, works, and is revoked", async ({ page, request }) => {
  const finish = watchConsole(page);
  // The person from the first test, whose invite code was used.
  await signIn(page, `${base}-invite`, "/account/keys/");
  await expect(page.getByRole("heading", { level: 1, name: "API keys" })).toBeVisible();
  await expectAccessible(page);

  await page.getByLabel("Name").fill("Journey key");
  await page.getByRole("button", { name: "Create key" }).click();
  const shown = page.getByTestId("new-key");
  await expect(shown).toBeVisible();
  await expect(page.getByText("You won't see it again.")).toBeVisible();
  const key = (await shown.textContent()) ?? "";
  expect(key).toMatch(/^idleo_[a-z2-7]{52}$/);

  // The key acts as the person: it reads their account, and can't make another key.
  const asKey = { Authorization: `Bearer ${key}` };
  expect((await request.get(`${API}/v1/me`, { headers: asKey })).status()).toBe(200);
  const another = await request.post(`${API}/v1/api-keys`, {
    headers: { ...asKey, "Idempotency-Key": "journey-another" },
    data: { name: "another", scope: "full" },
  });
  expect(another.status()).toBe(403);
  expect(((await another.json()) as { type: string }).type).toBe("/problems/browser-sign-in-required");

  // Done: the key is gone from the page, and the list has it by its first characters.
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText(key)).toHaveCount(0);
  await expect(page.getByText("Journey key", { exact: true })).toBeVisible();
  await expect(page.getByText(`${key.slice(0, 14)}…`)).toBeVisible();

  // Revoke, after being asked: the key stops working at once.
  await page.getByRole("button", { name: "Revoke Journey key" }).click();
  await expect(page.getByRole("dialog")).toContainText("Revoke Journey key?");
  await page.getByRole("dialog").getByRole("button", { name: "Revoke Journey key" }).click();
  await expect(page.getByText("Journey key", { exact: true })).toHaveCount(0);
  expect((await request.get(`${API}/v1/me`, { headers: asKey })).status()).toBe(401);
  finish();
});

test("a command line's sign-in is approved in the browser, and it gets its key", async ({
  page,
  request,
}) => {
  const finish = watchConsole(page);
  const started = await request.post(`${API}/v1/cli-logins`, { data: { deviceName: "journey-laptop" } });
  expect(started.status()).toBe(201);
  const login = (await started.json()) as { id: string; userCode: string; pollToken: string };
  const poll = () =>
    request.post(`${API}/v1/cli-logins/${login.id}/token`, { data: { pollToken: login.pollToken } });
  expect((await poll()).status()).toBe(400);

  await signIn(page, `${base}-invite`, `/cli-login/?code=${login.userCode}`);
  await expect(page.getByRole("heading", { name: "Sign in the command line?" })).toBeVisible();
  await expect(page.getByText("On journey-laptop")).toBeVisible();
  await expect(page.getByLabel("The code to compare")).toHaveText(login.userCode);
  await expectAccessible(page);
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("You can close this tab.")).toBeVisible();

  // The waiting command line is handed a full key, named for the computer, once.
  const done = await poll();
  expect(done.status()).toBe(200);
  const key = (await done.json()) as { key: string; name: string; scope: string };
  expect(key).toMatchObject({ name: "CLI on journey-laptop", scope: "full" });
  expect((await poll()).status()).toBe(400);
  expect(
    (await request.get(`${API}/v1/me`, { headers: { Authorization: `Bearer ${key.key}` } })).status(),
  ).toBe(200);
  finish();
});
