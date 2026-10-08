import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, signIn, type Answer } from "./support";

/**
 * Every screen in every state (web/DESIGN.md "States"), in light and dark, on a phone and a laptop: zero axe
 * violations, compared with its visual baseline, and copied into the review gallery when GALLERY_DIR is set.
 * The API answers from fixtures; sign-in goes through the local sign-in server.
 */

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.wallet },
  "/v1/sessions": { json: { sessions: [data.running, data.completed, data.failed] } },
  ...overrides,
});

let consoleErrors: string[] = [];

test.beforeEach(async ({ page }) => {
  consoleErrors = [];
  // Hydration mismatches and uncaught errors show here; a failed fixture request is expected in the error states.
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource")) {
      consoleErrors.push(message.text());
    }
  });
  page.on("pageerror", (error) => consoleErrors.push(error.message));
  await page.clock.setFixedTime(data.NOW);
});

test.afterEach(() => {
  expect(consoleErrors, "console errors").toEqual([]);
});

test.describe("signing in", () => {
  test("sign-in", async ({ page }, info) => {
    await page.goto("/sign-in/");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sign-in");
  });

  test("sign-in after the session expired", async ({ page }, info) => {
    await page.goto("/sign-in/?expired=1");
    await expect(page.getByText("Your sign-in has expired")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sign-in-expired");
  });

  test("sign-in with an invalid address", async ({ page }, info) => {
    await page.goto("/sign-in/");
    await page.getByLabel("Email", { exact: true }).fill("not-an-address");
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await expect(page.getByText("That doesn't look like an email address.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sign-in-invalid-email");
  });

  test("welcome, on a new account's first sign-in", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/me": { json: data.me(data.NOW.toISOString()) } }));
    await page.goto("/sign-in/");
    await page.getByLabel("Email", { exact: true }).fill("asha-new@example.com");
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await page.waitForURL("**/welcome/");
    await expect(page.getByText("₹499.40")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "welcome");
  });
});

test.describe("home", () => {
  test("with live and recent sessions", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha");
    await expect(page.getByRole("region", { name: "Running now" })).toBeVisible();
    await expect(page.getByText("₹13.60")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "home");
  });

  test("while loading", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/wallet": "hold", "/v1/sessions": "hold" }));
    await signIn(page, "asha");
    await expect(page.getByLabel("Loading your sessions")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "home-loading");
  });

  test("with no sessions yet", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/wallet": { json: data.emptyWallet }, "/v1/sessions": { json: { sessions: [] } } }),
    );
    await signIn(page, "asha");
    await expect(page.getByText("No sessions yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "home-empty");
  });

  test("when the API can't be reached", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/wallet": { status: 503 }, "/v1/sessions": { status: 503 } }));
    await signIn(page, "asha");
    await expect(page.getByText(/couldn't load your sessions/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "home-error");
  });

  test("with stale figures after a failed refresh", async ({ page }, info) => {
    const once = (json: unknown) => (call: number) => (call === 1 ? { json } : { status: 503 });
    await fakeApi(
      page,
      signedIn({
        "/v1/sessions": once({ sessions: [data.running, data.completed] }),
        "/v1/wallet": once(data.wallet),
      }),
    );
    await signIn(page, "asha");
    // The credit and the sessions refresh on their own timers: wait for both to have failed.
    await expect(page.getByText(/Couldn't refresh/)).toHaveCount(2, { timeout: 20_000 });
    await expectAccessible(page);
    await capture(page, info, "home-stale");
  });

  test("with credit running low", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/sessions": { json: { sessions: [{ ...data.running, lowCredit: true }] } } }),
    );
    await signIn(page, "asha");
    await expect(page.getByText("Your credit is running low.", { exact: false })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "home-low-credit");
  });

  test("with the user menu open", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha");
    await page.getByRole("button", { name: "Your account and settings" }).click();
    await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "home-user-menu");
  });
});

test.describe("session detail", () => {
  test("while it runs", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/sessions/.+": { json: data.running } }));
    await signIn(page, "asha", `/session/?id=${data.running.id}`);
    await expect(page.getByRole("heading", { name: "QLoRA fine-tune (Qwen 2.5 1.5B)" })).toBeVisible();
    await expect(page.getByText("₹13.60")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-running");
  });

  test("completed, with its outputs", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/sessions/[^/]+/outputs": { json: data.outputs },
        "/v1/sessions/[^/]+": { json: data.completed },
      }),
    );
    await signIn(page, "asha", `/session/?id=${data.completed.id}`);
    await expect(page.getByRole("link", { name: /result.json/ })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-completed");
  });

  test("failed", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/sessions/.+": { json: data.failed } }));
    await signIn(page, "asha", `/session/?id=${data.failed.id}`);
    await expect(page.getByText("It ran out of memory")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-failed");
  });

  test("while loading", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/sessions/.+": "hold" }));
    await signIn(page, "asha", `/session/?id=${data.running.id}`);
    await expect(page.getByLabel("Loading this session")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-loading");
  });

  test("not found", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/sessions/.+": { status: 404 } }));
    await signIn(page, "asha", "/session/?id=01a11300-0000-7000-8000-00000000dead");
    await expect(page.getByText(/couldn't find this session/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-not-found");
  });
});

test.describe("credit", () => {
  test("history, a page at a time", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/wallet/transactions": (call) => ({ json: data.history(call === 1 ? 0 : 1) }) }),
    );
    await signIn(page, "asha", "/credit/");
    await expect(page.getByRole("button", { name: "Show older" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "credit");
    await page.getByRole("button", { name: "Show older" }).click();
    await expect(page.getByRole("button", { name: "Show older" })).toHaveCount(0);
    await expect(page.getByText("Free credit", { exact: true }).first()).toBeVisible();
  });

  test("with no credit yet", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/wallet": { json: data.emptyWallet },
        "/v1/wallet/transactions": { json: { entries: [], nextCursor: null } },
      }),
    );
    await signIn(page, "asha", "/credit/");
    await expect(page.getByText("No credit yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "credit-empty");
  });

  test("when the history can't load", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/wallet/transactions": { status: 500 } }));
    await signIn(page, "asha", "/credit/");
    await expect(page.getByText(/couldn't load your credit history/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "credit-error");
  });
});

test.describe("the rest", () => {
  test("booking, coming next", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/book/");
    await expect(page.getByText("Booking is coming next")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "book");
  });

  test("a page that doesn't exist", async ({ page }, info) => {
    await page.goto("/no-such-page/");
    await expect(page.getByRole("heading", { name: "We can't find that page" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "not-found");
  });
});
