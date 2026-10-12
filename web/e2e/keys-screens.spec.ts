import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * Account → API keys and the page that approves a CLI sign-in (ADR 0025), every state, light and dark, phone and
 * laptop: zero axe violations, no console errors. The API answers from fixtures.
 */

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/api-keys": { json: data.apiKeys() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  ...overrides,
});

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test.describe("API keys", () => {
  test("lists each key by its name, first characters and scope, never the key", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/keys/");
    await expect(page.getByRole("heading", { level: 1, name: "API keys" })).toBeVisible();
    const list = page.getByRole("list").filter({ hasText: "CLI on asha-laptop" });
    await expect(list.getByRole("listitem")).toHaveCount(2);
    await expect(list.getByRole("listitem").first()).toContainText("idleo_k3j9x2mq…");
    await expect(list.getByRole("listitem").first()).toContainText("Full access");
    await expect(list.getByRole("listitem").first()).toContainText("used 12 min ago");
    await expect(list.getByRole("listitem").nth(1)).toContainText("Read only");
    await expect(list.getByRole("listitem").nth(1)).toContainText("never used");
    await expectAccessible(page);
    await capture(page, info, "keys");
  });

  test("is reached from Account", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/");
    await page.getByRole("link", { name: /API keys/ }).click();
    await expect(page).toHaveURL(/\/account\/keys\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "API keys" })).toBeVisible();
  });

  test("while the keys load", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/api-keys": "hold" }));
    await signIn(page, "asha", "/account/keys/");
    await expect(page.getByRole("status", { name: "Loading your API keys" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "keys-loading");
  });

  test("when the keys can't be loaded, with a retry that works", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/api-keys": { status: 503 } }));
    await signIn(page, "asha", "/account/keys/");
    await expect(page.getByText(/couldn't load your API keys/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "keys-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, signedIn());
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("CLI on asha-laptop")).toBeVisible();
  });

  test("with no keys yet", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/api-keys": { json: { keys: [] } } }));
    await signIn(page, "asha", "/account/keys/");
    await expect(page.getByText("No keys yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "keys-empty");
  });

  test("keeps the keys and says so when a refresh fails", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/api-keys": (call) => (call === 1 ? { json: data.apiKeys() } : { status: 503 }) }),
    );
    await signIn(page, "asha", "/account/keys/");
    await expect(page.getByText("CLI on asha-laptop")).toBeVisible();
    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    await expect(page.getByText(/Couldn't refresh/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("CLI on asha-laptop")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "keys-stale");
  });

  test("makes a key, shows it once with a warning, and lists it", async ({ page, context }, info) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    let made = false;
    let sent: { name: string; scope: string; key: string | undefined } | null = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/api-keys": (_call, request) => {
          if (request.method() === "POST") {
            const body = request.postDataJSON() as { name: string; scope: string };
            sent = { ...body, key: request.headers()["idempotency-key"] };
            made = true;
            return { json: data.newApiKey(body.name, "read"), status: 201 };
          }
          const list = data.apiKeys();
          return {
            json: made
              ? {
                  keys: [
                    { ...data.newApiKey("CI box", "read"), key: undefined, createdVia: "web" },
                    ...list.keys,
                  ],
                }
              : list,
          };
        },
      }),
    );
    await signIn(page, "asha", "/account/keys/");
    await page.getByLabel("Name").fill("  CI box ");
    await page.getByLabel("Read only").check();
    await expectAccessible(page);
    await capture(page, info, "keys-create");
    await page.getByRole("button", { name: "Create key" }).click();

    await expect(page.getByTestId("new-key")).toHaveText(data.newApiKey("CI box", "read").key);
    await expect(page.getByText("Copy it now. You won't see it again.")).toBeVisible();
    expect(sent).toMatchObject({ name: "CI box", scope: "read" });
    expect(sent!.key).toMatch(/^[0-9a-f-]{36}$/);
    await expectAccessible(page);
    await capture(page, info, "keys-created");
    await page.getByRole("button", { name: "Copy key" }).click();
    await expect(page.getByRole("button", { name: "Copied" })).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
      data.newApiKey("CI box", "read").key,
    );

    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByTestId("new-key")).toHaveCount(0);
    await expect(page.getByText(data.newApiKey("CI box", "read").key)).toHaveCount(0);
    await expect(page.getByText("CI box", { exact: true })).toBeVisible();
  });

  test("never keeps the key after the page is left", async ({ page }) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/api-keys": (_call, request) =>
          request.method() === "POST"
            ? { json: data.newApiKey("CI box", "full"), status: 201 }
            : { json: data.apiKeys() },
      }),
    );
    await signIn(page, "asha", "/account/keys/");
    await page.getByLabel("Name").fill("CI box");
    await page.getByRole("button", { name: "Create key" }).click();
    await expect(page.getByTestId("new-key")).toBeVisible();
    await page.goto("/account/keys/");
    await expect(page.getByTestId("new-key")).toHaveCount(0);
    const stored = await page.evaluate(() =>
      [localStorage, sessionStorage].map((store) =>
        Object.keys(store)
          .map((name) => `${name}=${store.getItem(name) ?? ""}`)
          .join("\n"),
      ),
    );
    expect(stored.join("\n")).not.toContain("idleo_z5w2n9ca");
  });

  test("says when a key can't be made", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/api-keys": (_call, request) =>
          request.method() === "POST"
            ? { status: 409, problem: "too-many-api-keys" }
            : { json: data.apiKeys() },
      }),
    );
    await signIn(page, "asha", "/account/keys/");
    await page.getByLabel("Name").fill("one too many");
    await page.getByRole("button", { name: "Create key" }).click();
    await expect(page.getByRole("alert")).toHaveText(
      "You have 20 API keys. Revoke one you no longer use, then create this one.",
    );
    await expectAccessible(page);
    await capture(page, info, "keys-too-many");
  });

  test("asks for a name before making a key", async ({ page }) => {
    let posts = 0;
    await fakeApi(
      page,
      signedIn({
        "/v1/api-keys": (_call, request) => {
          if (request.method() === "POST") {
            posts++;
          }
          return { json: data.apiKeys() };
        },
      }),
    );
    await signIn(page, "asha", "/account/keys/");
    await page.getByRole("button", { name: "Create key" }).click();
    await expect(page.getByText(/Give the key a name/)).toBeVisible();
    expect(posts).toBe(0);
  });

  test("asks before revoking, then the key is gone", async ({ page }, info) => {
    let revoked = false;
    await fakeApi(
      page,
      signedIn({
        "/v1/api-keys/[^/]+": (_call, request) => {
          if (request.method() === "DELETE") {
            revoked = true;
            return { json: {}, status: 204 };
          }
          return { status: 404 };
        },
        "/v1/api-keys": () => ({
          json: revoked ? { keys: data.apiKeys().keys.slice(0, 1) } : data.apiKeys(),
        }),
      }),
    );
    await signIn(page, "asha", "/account/keys/");
    await page.getByRole("button", { name: "Revoke Dashboard" }).click();
    await expect(page.getByRole("dialog")).toContainText("Revoke Dashboard?");
    await expect(page.getByRole("dialog")).toContainText("stops working at once");
    await expectAccessible(page);
    await capture(page, info, "keys-revoke");
    await page.getByRole("dialog").getByRole("button", { name: "Revoke Dashboard" }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByText("Dashboard", { exact: true })).toHaveCount(0);
    await expect(page.getByText("CLI on asha-laptop")).toBeVisible();
  });

  test("keeping a key leaves it in the list", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/keys/");
    await page.getByRole("button", { name: "Revoke Dashboard" }).click();
    await page.getByRole("button", { name: "Keep it" }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByText("Dashboard", { exact: true })).toBeVisible();
  });
});

test.describe("approving a CLI sign-in", () => {
  const CODE = "WDJB-MJHT";
  const login = (overrides: Record<string, Answer> = {}) =>
    signedIn({ "/v1/cli-logins": { json: data.pendingCliLogin() }, ...overrides });

  test("shows the device and the code to compare, and approves", async ({ page }, info) => {
    let approved = false;
    await fakeApi(
      page,
      login({
        "/v1/cli-logins/[^/]+/approve": () => {
          approved = true;
          return { json: data.pendingCliLogin() };
        },
      }),
    );
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await expect(page.getByRole("heading", { name: "Sign in the command line?" })).toBeVisible();
    await expect(page.getByText("On asha-laptop")).toBeVisible();
    await expect(page.getByLabel("The code to compare")).toHaveText(CODE);
    await expectAccessible(page);
    await capture(page, info, "cli-login");
    await page.getByRole("button", { name: "Approve" }).click();
    await expect(page.getByText("You can close this tab.")).toBeVisible();
    expect(approved).toBe(true);
    await expectAccessible(page);
    await capture(page, info, "cli-login-approved");
  });

  test("Cancel approves nothing", async ({ page }, info) => {
    let approved = false;
    await fakeApi(
      page,
      login({
        "/v1/cli-logins/[^/]+/approve": () => {
          approved = true;
          return { json: data.pendingCliLogin() };
        },
      }),
    );
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await page.getByRole("button", { name: "Cancel" }).click();
    await expect(page.getByText("Nothing was approved.")).toBeVisible();
    expect(approved).toBe(false);
    await expectAccessible(page);
    await capture(page, info, "cli-login-cancelled");
  });

  test("while it's looked up", async ({ page }, info) => {
    await fakeApi(page, login({ "/v1/cli-logins": "hold" }));
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await expect(page.getByRole("status", { name: "Loading the sign-in" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "cli-login-loading");
  });

  test("a sign-in that has expired or was used says so and what to do", async ({ page }, info) => {
    await fakeApi(page, login({ "/v1/cli-logins": { status: 404, problem: "not-found" } }));
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await expect(page.getByRole("heading", { name: "That sign-in has ended" })).toBeVisible();
    await expect(page.getByText(/Run idleo login again/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "cli-login-ended");
  });

  test("an error that isn't the code's, with a retry", async ({ page }, info) => {
    await fakeApi(page, login({ "/v1/cli-logins": { status: 503 } }));
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await expect(page.getByRole("heading", { name: "We couldn't check that sign-in" })).toBeVisible({
      timeout: 15_000,
    });
    await expectAccessible(page);
    await capture(page, info, "cli-login-error");
  });

  test("an approval the server refuses is said in words, and can be tried again", async ({ page }, info) => {
    await fakeApi(
      page,
      login({ "/v1/cli-logins/[^/]+/approve": { status: 400, problem: "cli-login-expired" } }),
    );
    await signIn(page, "asha", `/cli-login/?code=${CODE}`);
    await page.getByRole("button", { name: "Approve" }).click();
    await expect(page.getByRole("alert")).toHaveText(
      "That sign-in link has expired or was already used. Run idleo login again.",
    );
    await expect(page.getByRole("button", { name: "Approve" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "cli-login-refused");
  });

  test("without a code, says how to get one", async ({ page }) => {
    await fakeApi(page, login());
    await signIn(page, "asha", "/cli-login/");
    await expect(page.getByText(/Run idleo login in a terminal/)).toBeVisible();
  });
});
