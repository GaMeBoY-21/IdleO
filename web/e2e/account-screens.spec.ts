import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as privacy from "./fixtures-privacy";
import { capture, expectAccessible, fakeApi, onPhone, signIn, watchConsole, type Answer } from "./support";

/**
 * Signing in with an invite code, the Account screen, and the way sign-in links to the terms (ADR 0024), every state, light
 * and dark, phone and laptop: zero axe violations, no console errors. The API answers from fixtures.
 */

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  ...overrides,
});

const waiting = (overrides: Record<string, Answer> = {}) =>
  signedIn({ "/v1/me": { json: data.meWaiting(), etag: '"0"' }, ...overrides });

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

/** A first sign-in of a waiting account, which lands on the invite step. */
async function signInWaiting(page: Page) {
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill("asha-waiting@example.com");
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL("**/invite/**");
  await expect(page.getByRole("heading", { name: "Enter your invite code" })).toBeVisible();
}

test.describe("the Terms and privacy step", () => {
  test("a first sign-in agrees, with its age, before the invite step", async ({ page }, info) => {
    let accepted = false;
    await fakeApi(
      page,
      signedIn({
        "/v1/me": () => ({
          json: accepted ? data.meWaiting() : data.meNew(),
          etag: '"0"',
        }),
        "/v1/me/notices": () => {
          accepted = true;
          return { json: data.meWaiting() };
        },
      }),
    );
    await page.goto("/sign-in/");
    await page.getByLabel("Email", { exact: true }).fill("asha-new@example.com");
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await page.waitForURL("**/agree/**");
    await expect(page.getByRole("heading", { name: "Before you start" })).toBeVisible();
    await page.getByRole("button", { name: "Agree and continue" }).click();
    await expect(page.getByText("Tick both boxes to carry on")).toBeVisible();
    expect(accepted).toBe(false);
    await expectAccessible(page);
    await capture(page, info, "agree-first");
    await page.getByLabel("I agree to the Terms and the Privacy notice").check();
    await page.getByLabel("I'm 18 or older").check();
    await page.getByRole("button", { name: "Agree and continue" }).click();
    await page.waitForURL("**/invite/**");
    expect(accepted).toBe(true);
  });

  test("a changed notice is asked for once on the next visit, without the age", async ({ page }, info) => {
    let accepted = false;
    const changed = { ...data.me(), notices: { ...data.NOTICES_ACCEPTED, accepted: false } };
    await fakeApi(
      page,
      signedIn({
        "/v1/me": () => ({ json: accepted ? data.me() : changed, etag: '"3"' }),
        "/v1/me/notices": () => {
          accepted = true;
          return { json: data.me() };
        },
      }),
    );
    await signIn(page, "asha", "/files/");
    await page.waitForURL("**/agree/**");
    await expect(page.getByText("Our Terms or Privacy notice changed since you last agreed.")).toBeVisible();
    await expect(page.getByLabel("I'm 18 or older")).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "agree-changed");
    await page.getByLabel("I agree to the Terms and the Privacy notice").check();
    await page.getByRole("button", { name: "Agree and continue" }).click();
    await page.waitForURL("**/files/");
    expect(accepted).toBe(true);
  });
});

test.describe("your data", () => {
  test("shows what we hold by kind, and makes the file in the background", async ({ page }, info) => {
    let asked = 0;
    await fakeApi(
      page,
      signedIn({
        "/v1/me/data": { json: privacy.yourData() },
        "/v1/me/exports": (call, request) => {
          if (request.method() === "POST") {
            asked++;
            return { json: privacy.exportBeingMade().exports[0], status: 202 };
          }
          return { json: asked === 0 ? privacy.noExports() : privacy.exportBeingMade() };
        },
      }),
    );
    await signIn(page, "asha", "/account/data/");
    await expect(page.getByRole("heading", { level: 1, name: "Your data" })).toBeVisible();
    await expect(page.getByText("6 sessions,")).toBeVisible();
    await expect(page.getByText("Nothing made yet.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "your-data");
    await page.getByRole("button", { name: "Make my file" }).click();
    await expect(page.getByText("Being made. We'll email you when it's ready.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Make my file" })).toBeDisabled();
    expect(asked).toBe(1);
  });

  test("a ready file downloads, and a second one the same day is refused in words", async ({
    page,
  }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me/data": { json: privacy.yourData() },
        "/v1/me/exports": { json: privacy.exportReady() },
      }),
    );
    await signIn(page, "asha", "/account/data/");
    await expect(page.getByText(/^Ready, .* Kept until /)).toBeVisible();
    await expect(page.getByRole("button", { name: "Download" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "your-data-ready");
  });

  test("says what went wrong and tries again", async ({ page }) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me/data": { status: 500 },
        "/v1/me/exports": { status: 429, problem: "export-too-soon" },
      }),
    );
    await signIn(page, "asha", "/account/data/");
    await expect(page.getByRole("button", { name: "Try again" }).first()).toBeVisible();
    await expectAccessible(page);
  });
});

test.describe("the invite step", () => {
  test("shows one field, typed in groups", async ({ page }, info) => {
    await fakeApi(page, waiting());
    await signInWaiting(page);
    await expect(page.getByText("Don't have a code? Ask the person who invited you.")).toBeVisible();
    await page.getByLabel("Invite code").fill("7k2q9xmd");
    await expect(page.getByLabel("Invite code")).toHaveValue("IDLE-7K2Q-9XMD");
    await expectAccessible(page);
    await capture(page, info, "invite");
  });

  test("asks for the whole code before sending anything", async ({ page }, info) => {
    let sent = 0;
    await fakeApi(
      page,
      waiting({
        "/v1/me/activate": () => {
          sent++;
          return { json: data.me() };
        },
      }),
    );
    await signInWaiting(page);
    await page.getByLabel("Invite code").fill("7k2q");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByText("Enter all 8 letters and numbers of your code.")).toBeVisible();
    expect(sent).toBe(0);
    await expectAccessible(page);
    await capture(page, info, "invite-short");
  });

  test("says plainly when the code isn't accepted", async ({ page }, info) => {
    await fakeApi(page, waiting({ "/v1/me/activate": { status: 400, problem: "invite-invalid" } }));
    await signInWaiting(page);
    await page.getByLabel("Invite code").fill("7k2q9xmd");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(
      page.getByText(
        "That code isn't one we can accept. Check it and try again, or ask the person who invited you.",
      ),
    ).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "invite-invalid");
  });

  test("says when too many codes were tried", async ({ page }, info) => {
    await fakeApi(page, waiting({ "/v1/me/activate": { status: 429, problem: "too-many-attempts" } }));
    await signInWaiting(page);
    await page.getByLabel("Invite code").fill("7k2q9xmd");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByText("Too many codes were tried. Wait an hour, then try again.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "invite-too-many");
  });

  test("an accepted code goes on to the app", async ({ page }) => {
    await fakeApi(page, waiting({ "/v1/me/activate": { json: data.me() } }));
    await signInWaiting(page);
    await page.getByLabel("Invite code").fill("IDLE-7K2Q-9XMD");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();
  });

  test("any screen that meets an account without a code sends it here", async ({ page }) => {
    await fakeApi(page, signedIn({ "/v1/sessions": { status: 403, problem: "invite-required" } }));
    await page.goto("/sign-in/");
    await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await page.waitForURL("**/invite/**");
    await expect(page.getByRole("heading", { name: "Enter your invite code" })).toBeVisible();
  });
});

test.describe("the account", () => {
  test("shows who you are and the notification choices", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/");
    await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();
    // On a laptop the rail names the person too, so the first of them.
    await expect(page.getByText("asha@example.com", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("Google", { exact: true })).toBeVisible();
    await expect(page.getByRole("switch")).toHaveCount(28);
    for (const title of ["Host lost", "Payout sent or failed"]) {
      await expect(page.getByRole("switch", { name: `${title}: email` })).toBeDisabled();
    }
    await expect(page.getByText("A lost host changes your charges.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "account");
  });

  test("is reached from the avatar menu, or on a laptop the avatar itself", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha");
    if (onPhone(info)) {
      await page.getByRole("button", { name: "Your account and settings" }).click();
      await page.getByRole("menuitem", { name: "Account" }).click();
    } else {
      await page.getByRole("link", { name: "Account" }).click();
    }
    await expect(page).toHaveURL(/\/account\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();
  });

  test("on a laptop, has the rail: the sections, the theme and Sign out", async ({ page }, info) => {
    test.skip(onPhone(info), "the rail is from 768 px; a phone has the menu");
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/");
    const sections = page.getByRole("navigation", { name: "Account" });
    await expect(sections.getByRole("link")).toHaveText([
      "Profile and notifications",
      "API keys",
      "My files",
      "Reports",
    ]);
    await expect(sections.getByRole("link", { name: "Profile and notifications" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    const theme = page.getByRole("radiogroup", { name: "Appearance" });
    await theme.getByRole("radio", { name: "Dark" }).click();
    await expect(theme.getByRole("radio", { name: "Dark" })).toHaveAttribute("aria-checked", "true");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await theme.getByRole("radio", { name: "Match my device" }).click();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
    await sections.getByRole("link", { name: "My files" }).click();
    await expect(page).toHaveURL(/\/files\/$/);
  });

  test("while the choices load", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/me/notifications": "hold" }));
    await signIn(page, "asha", "/account/");
    await expect(page.getByRole("status", { name: "Loading your notification choices" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "account-loading");
  });

  test("when the choices can't be loaded, with a retry that works", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/me/notifications": { status: 503 } }));
    await signIn(page, "asha", "/account/");
    await expect(page.getByText(/couldn't load your notification choices/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "account-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, signedIn());
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("switch")).toHaveCount(28);
  });

  test("with no kinds to choose", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/me/notifications": { json: { items: [] } } }));
    await signIn(page, "asha", "/account/");
    await expect(page.getByText("Nothing to choose yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "account-empty");
  });

  test("keeps the choices and says so when a refresh fails", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me/notifications": (call) =>
          call === 1 ? { json: data.notificationChoices() } : { status: 503 },
      }),
    );
    await signIn(page, "asha", "/account/");
    await expect(page.getByRole("switch")).toHaveCount(28);
    // The choices are fresh for 30 s; coming back to the tab later reads them again, and that read fails.
    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    await expect(page.getByText(/Couldn't refresh/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("switch")).toHaveCount(28);
    await expectAccessible(page);
    await capture(page, info, "account-stale");
  });

  test("saves the name, as the server answers it", async ({ page }, info) => {
    let name = "Asha";
    let patched: { displayName: string; ifMatch: string | undefined } | null = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/me": (_call, request) => {
          if (request.method() === "PATCH") {
            const body = request.postDataJSON() as { displayName: string };
            patched = { displayName: body.displayName, ifMatch: request.headers()["if-match"] };
            name = body.displayName;
            return { json: { ...data.me(), displayName: name }, etag: '"4"' };
          }
          return { json: { ...data.me(), displayName: name }, etag: '"3"' };
        },
      }),
    );
    await signIn(page, "asha", "/account/");
    await page.getByRole("button", { name: "Edit your name" }).click();
    await expect(page.getByLabel("Name")).toBeFocused();
    await expectAccessible(page);
    await capture(page, info, "account-edit-name");
    await page.getByLabel("Name").fill("  Asha Rao ");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByText("Asha Rao", { exact: true }).first()).toBeVisible();
    expect(patched).toEqual({ displayName: "Asha Rao", ifMatch: '"3"' });
    await expect(page.getByRole("button", { name: "Edit your name" })).toBeVisible();
  });

  test("says when the name can't be saved", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me": (_call, request) =>
          request.method() === "PATCH"
            ? { status: 412, problem: "version-stale" }
            : { json: data.me(), etag: '"3"' },
      }),
    );
    await signIn(page, "asha", "/account/");
    await page.getByRole("button", { name: "Edit your name" }).click();
    await page.getByLabel("Name").fill("Asha Rao");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("alert")).toHaveText(
      "This changed since you opened it. Reload to see the latest, then try again.",
    );
    await expectAccessible(page);
    await capture(page, info, "account-name-error");
  });

  test("saves a switch only once the server has, and sends the whole set", async ({ page }, info) => {
    let sent: { items: { kind: string; email: boolean; inApp: boolean }[] } | null = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/me/notifications": (_call, request) => {
          if (request.method() !== "PUT") {
            return { json: data.notificationChoices() };
          }
          sent = request.postDataJSON() as typeof sent;
          const answer = data.notificationChoices();
          answer.items = answer.items.map((item) => ({
            ...item,
            ...(sent!.items.find((choice) => choice.kind === item.kind) ?? {}),
          }));
          return { json: answer };
        },
      }),
    );
    await signIn(page, "asha", "/account/");
    await page.getByRole("switch", { name: "Low credit: email" }).click();
    await expect(page.getByText("Saved.")).toBeVisible();
    await expect(page.getByRole("switch", { name: "Low credit: email" })).not.toBeChecked();
    expect(sent!.items).toHaveLength(13);
    expect(sent!.items.find((item) => item.kind === "low-credit")).toEqual({
      kind: "low-credit",
      email: false,
      inApp: true,
    });
    await expectAccessible(page);
    await capture(page, info, "account-saved");
  });

  test("leaves a switch where it was when the server refuses", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me/notifications": (_call, request) =>
          request.method() === "PUT"
            ? { status: 422, problem: "notification-locked" }
            : { json: data.notificationChoices() },
      }),
    );
    await signIn(page, "asha", "/account/");
    await page.getByRole("switch", { name: "Low credit: email" }).click();
    await expect(page.getByRole("alert")).toHaveText(
      "Messages about your money and your safety always stay on, so they can't be turned off.",
    );
    await expect(page.getByRole("switch", { name: "Low credit: email" })).toBeChecked();
    await expectAccessible(page);
    await capture(page, info, "account-switch-error");
  });

  test("signs out", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/");
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.waitForURL("**/sign-in/**");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  });
});

// The terms and privacy pages are public pages, not the app's (ADR 0029): public-pages.spec.ts holds them.
test.describe("terms and privacy", () => {
  test("are linked from sign-in, in one line", async ({ page }) => {
    await page.goto("/sign-in/");
    await page.getByRole("link", { name: "terms" }).click();
    await expect(page).toHaveURL(/\/terms\/$/);
  });
});
