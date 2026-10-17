import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, onPhone, signIn, watchConsole, type Answer } from "./support";

/**
 * My files (ADR 0022) in every state, light and dark, phone and laptop: the renter's uploads with their size, files
 * and last use; deleting one, asked once more in its row; one a running session uses, which can't be deleted yet;
 * nothing yet; loading; and an error with a retry. Reached from the account menu.
 */
function api(overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    "/v1/uploads": { json: data.uploadsPage() },
    ...overrides,
  };
}

let checkConsole: () => void;
test.beforeEach(async ({ page }) => {
  checkConsole = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => checkConsole());

const list = (page: import("@playwright/test").Page) => page.getByRole("list", { name: "Your files" });

test("my files, from the account menu", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/");
  if (onPhone(info)) {
    await page.getByRole("button", { name: "Your account and settings" }).click();
    await page.getByRole("menuitem", { name: "My files" }).click();
  } else {
    // On a laptop the avatar is a link to Account, whose rail has My files.
    await page.getByRole("link", { name: "Account" }).click();
    await page.getByRole("navigation", { name: "Account" }).getByRole("link", { name: "My files" }).click();
  }
  await expect(page).toHaveURL(/\/files\/$/);
  await expect(page.getByRole("heading", { level: 1, name: "My files" })).toBeVisible();
  await expect(list(page).getByRole("listitem")).toHaveCount(4);
  await expect(list(page).getByRole("listitem").first()).toContainText(
    "llama-3-70b/38 GB · 31 files · uploaded 2 h ago",
  );
  await expect(list(page).getByRole("listitem").nth(2)).toContainText(
    "my-llm-7b/4.6 GB · 4 files · last used 22 min ago",
  );
  await expectAccessible(page);
  await capture(page, info, "files");
});

test("deleting one asks once more, in its row", async ({ page }, info) => {
  let deleted = false;
  await fakeApi(
    page,
    api({
      "/v1/uploads/[^/]+": (_call, request) => {
        if (request.method() === "DELETE") {
          deleted = true;
          return { json: {} };
        }
        return { status: 404 };
      },
      "/v1/uploads": () => ({ json: data.uploadsPage(deleted ? data.myFiles.slice(1) : data.myFiles) }),
    }),
  );
  await signIn(page, "asha", "/files/");
  await page.getByRole("button", { name: "Delete llama-3-70b/" }).click();
  await expect(page.getByRole("button", { name: "Delete for good" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "files-delete");
  await page.getByRole("button", { name: "Delete for good" }).click();
  await expect(list(page).getByRole("listitem")).toHaveCount(3);
});

test("one a running session uses can't be deleted yet", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/uploads/[^/]+": { status: 409, problem: "upload-in-use" } }));
  await signIn(page, "asha", "/files/");
  await page.getByRole("button", { name: "Delete my-llm-7b/" }).click();
  await page.getByRole("button", { name: "Delete for good" }).click();
  await expect(page.getByRole("alert")).toHaveText(
    "A session that hasn't ended uses this upload. You can delete it once the session ends.",
  );
  await expectAccessible(page);
  await capture(page, info, "files-in-use");
});

test("nothing yet", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/uploads": { json: data.uploadsPage([]) } }));
  await signIn(page, "asha", "/files/");
  await expect(page.getByText("Nothing here yet")).toBeVisible();
  await expect(page.getByRole("link", { name: "Book a GPU" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "files-empty");
});

test("loading, then an error with a retry", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/uploads": "hold" }));
  await signIn(page, "asha", "/files/");
  await expect(page.getByRole("status", { name: "Loading your files" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "files-loading");
  await page.unrouteAll({ behavior: "ignoreErrors" });
  await fakeApi(page, api({ "/v1/uploads": { status: 503 } }));
  await page.reload();
  await expect(page.getByRole("button", { name: "Try again" })).toBeVisible({ timeout: 15_000 });
  await expectAccessible(page);
  await capture(page, info, "files-error");
});
