import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * The bell, the list of notifications and the banners for what arrives while the app is open (ADR 0026), every
 * state, light and dark, phone and laptop: zero axe violations, no console errors. The API answers from fixtures.
 */

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: { ...data.me(), unreadNotifications: 3 }, etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/notifications": { json: data.inbox(3) },
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

test.describe("the bell", () => {
  test("shows how many are unread and opens the list", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/");
    const bell = page.getByRole("link", { name: "Notifications, 3 unread" });
    await expect(bell).toBeVisible();
    await expect(bell).toContainText("3");
    await bell.click();
    await expect(page).toHaveURL(/\/notifications\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "Notifications" })).toBeVisible();
  });

  test("has no number when everything is read", async ({ page }) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/me": { json: data.me(), etag: '"3"' },
        "/v1/notifications": { json: data.inbox(0) },
      }),
    );
    await signIn(page, "asha", "/");
    const bell = page.getByRole("link", { name: "Notifications", exact: true });
    await expect(bell).toBeVisible();
    await expect(bell).not.toContainText(/\d/);
  });

  test("says 9+ for ten or more", async ({ page }) => {
    await fakeApi(page, signedIn({ "/v1/notifications": { json: { ...data.inbox(3), unread: 14 } } }));
    await signIn(page, "asha", "/");
    await expect(page.getByRole("link", { name: "Notifications, 14 unread" })).toContainText("9+");
  });
});

test.describe("the list", () => {
  test("shows each message with its words, newest first, unread ones in bold", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByText("3 unread")).toBeVisible();
    const rows = page.getByRole("main").getByRole("listitem");
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0)).toContainText("Your credit is running low");
    await expect(rows.nth(1)).toContainText("Fine-tune a model finished");
    await expect(rows.nth(1)).toContainText("It cost ₹");
    await expect(rows.nth(2)).toContainText("The job ran out of memory.");
    await expect(rows.nth(3)).toContainText("No GPU was free");
    await expect(rows.nth(0)).toContainText("Unread:");
    await expect(rows.nth(3)).not.toContainText("Unread:");
    await expectAccessible(page);
    await capture(page, info, "notifications");
  });

  test("while the list loads", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/notifications": "hold" }));
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByRole("status", { name: "Loading your notifications" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "notifications-loading");
  });

  test("when it can't be loaded, with a retry that works", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/notifications": { status: 503 } }));
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByText(/couldn't load your notifications/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "notifications-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, signedIn());
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("Your credit is running low")).toBeVisible();
  });

  test("with nothing yet", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({ "/v1/notifications": { json: { items: [], nextCursor: null, unread: 0 } } }),
    );
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByText("Nothing yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Mark all read" })).toBeDisabled();
    await expectAccessible(page);
    await capture(page, info, "notifications-empty");
  });

  test("keeps the list and says so when a refresh fails", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications": (call) => (call === 1 ? { json: data.inbox(3) } : { status: 503 }),
      }),
    );
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByText("Your credit is running low")).toBeVisible();
    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    await expect(page.getByText(/Couldn't refresh/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("Your credit is running low")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "notifications-stale");
  });

  test("pages through older ones", async ({ page }) => {
    const first = data.inbox(3);
    const older = data.inbox(0).items.map((item) => ({ ...item, id: item.id.replace("-4000", "-3000") }));
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications": (_call, request) =>
          new URL(request.url()).searchParams.has("cursor")
            ? { json: { items: older, nextCursor: null, unread: 3 } }
            : { json: { ...first, nextCursor: "next-page" } },
      }),
    );
    await signIn(page, "asha", "/notifications/");
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(4);
    await page.getByRole("button", { name: "Show older" }).click();
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(8);
    await expect(page.getByRole("button", { name: "Show older" })).toHaveCount(0);
  });

  test("marks everything read, moving the bell with it", async ({ page }) => {
    let read: unknown = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications/read": (_call, request) => {
          read = request.postDataJSON();
          return { json: { unread: 0 } };
        },
      }),
    );
    await signIn(page, "asha", "/notifications/");
    await page.getByRole("button", { name: "Mark all read" }).click();
    await expect(page.getByText("All read", { exact: true })).toBeVisible();
    await expect.poll(() => read).toEqual({ upTo: data.inbox().items[0]!.id });
    await expect(page.getByRole("link", { name: "Notifications", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Mark all read" })).toBeDisabled();
    await expect(page.getByText("Unread:")).toHaveCount(0);
  });

  test("opening one goes to its session and marks it read", async ({ page }) => {
    let read: unknown = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications/read": (_call, request) => {
          read = request.postDataJSON();
          return { json: { unread: 2 } };
        },
        [`/v1/sessions/${data.completed.id}`]: { json: data.completed },
        [`/v1/sessions/${data.completed.id}/outputs`]: { json: data.outputs },
        "/v1/sessions/[^/]+/events": "hold",
        "/v1/sessions/[^/]+/logs": { json: { chunks: [], nextCursor: null, complete: true } },
      }),
    );
    await signIn(page, "asha", "/notifications/");
    await page.getByRole("link", { name: /Fine-tune a model finished/ }).click();
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${data.completed.id}`));
    await expect.poll(() => read).toEqual({ id: data.inbox().items[1]!.id });
  });
});

test.describe("what arrives while the app is open", () => {
  test("a banner pushes the screen down instead of covering it, and opening it marks it read", async ({
    page,
  }, info) => {
    let read: unknown = null;
    const arrived = { ...data.inbox(3), items: [data.newMessage(), ...data.inbox(3).items], unread: 4 };
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications": (call) => ({ json: call === 1 ? data.inbox(3) : arrived }),
        "/v1/notifications/read": (_call, request) => {
          read = request.postDataJSON();
          return { json: { unread: 3 } };
        },
        [`/v1/sessions/${data.stopped.id}`]: { json: data.stopped },
        [`/v1/sessions/${data.stopped.id}/outputs`]: { json: data.outputs },
        "/v1/sessions/[^/]+/events": "hold",
        "/v1/sessions/[^/]+/logs": { json: { chunks: [], nextCursor: null, complete: true } },
      }),
    );
    const firstList = page.waitForResponse((r) => r.url().includes("/v1/notifications?"));
    await signIn(page, "asha", "/sessions/");
    const heading = page.getByRole("heading", { level: 1 });
    await expect(heading).toBeVisible();
    await firstList;
    await expect(page.getByRole("link", { name: "Notifications, 3 unread" })).toBeVisible();
    await expect(page.getByRole("region", { name: "New notifications" })).toHaveCount(0);
    const before = (await heading.boundingBox())!.y;

    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    const banners = page.getByRole("region", { name: "New notifications" });
    await expect(banners).toBeVisible({ timeout: 15_000 });
    await expect(banners).toContainText("Fine-tune a model finished");
    await expect(page.getByRole("link", { name: "Notifications, 4 unread" })).toBeVisible();
    const banner = (await banners.boundingBox())!;
    const after = (await heading.boundingBox())!.y;
    expect(after, "the screen moved down by the banner, not under it").toBeGreaterThanOrEqual(
      banner.y + banner.height,
    );
    expect(after).toBeGreaterThan(before);
    await expectAccessible(page);
    await capture(page, info, "notifications-banner");

    await banners.getByRole("link", { name: "Fine-tune a model finished" }).click();
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${data.stopped.id}`));
    await expect.poll(() => read).toEqual({ id: data.newMessage().id });
  });

  test("dismissing a banner only hides it", async ({ page }) => {
    const arrived = { ...data.inbox(3), items: [data.newMessage(), ...data.inbox(3).items], unread: 4 };
    let marked = false;
    await fakeApi(
      page,
      signedIn({
        "/v1/notifications": (call) => ({ json: call === 1 ? data.inbox(3) : arrived }),
        "/v1/notifications/read": () => {
          marked = true;
          return { json: { unread: 0 } };
        },
      }),
    );
    const firstList = page.waitForResponse((r) => r.url().includes("/v1/notifications?"));
    await signIn(page, "asha", "/sessions/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await firstList;
    await page.clock.setFixedTime(new Date(data.NOW.getTime() + 10 * 60_000));
    await page.evaluate(() => window.dispatchEvent(new Event("visibilitychange")));
    const banners = page.getByRole("region", { name: "New notifications" });
    await expect(banners).toBeVisible({ timeout: 15_000 });
    await banners.getByRole("button", { name: /Dismiss/ }).click();
    await expect(banners).toHaveCount(0);
    expect(marked).toBe(false);
    await expect(page.getByRole("link", { name: "Notifications, 4 unread" })).toBeVisible();
  });
});
