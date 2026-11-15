import { readFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import { routes } from "./fixtures-admin-routes";
import { codesShown, expectAccessible, fakeApi, onPhone, signIn, type Answer, watchConsole } from "./support";
import { ADMIN_URL } from "./sites";

// The console is the admin site's alone (ADR 0058): every address here is on it.
test.use({ baseURL: ADMIN_URL });

/**
 * The console's finish (ADR 0057): keyboard-first, the one table, bulk from any list, one look and its budgets, across
 * every screen. The API answers from fixtures.
 */

const SCREENS = [
  ...readFileSync(new URL("../src/features/admin/AdminConsole.tsx", import.meta.url), "utf8").matchAll(
    /\{ href: "(\/admin\/[^"]*)", label: "([^"]+)"/g,
  ),
].map((match) => ({ href: match[1]!, label: match[2]! }));

async function open(page: Page, path: string, overrides: Record<string, Answer> = {}) {
  await fakeApi(page, routes(overrides));
  await signIn(page, "asha", path);
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
}

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test.describe("keys", () => {
  test("g then a letter goes to each screen, / searches the list, ? says every key and the runbook", async ({
    page,
  }) => {
    await open(page, "/admin/");
    await expect(page.getByRole("heading", { level: 1, name: "Control room" })).toBeVisible();
    for (const [key, title] of [
      ["i", "Inbox"],
      ["u", "Users"],
      ["h", "Hosts"],
      ["s", "Sessions"],
      ["m", "Money"],
      ["a", "Approvals"],
      ["c", "Control room"],
    ] as const) {
      await page.keyboard.press("g");
      await page.keyboard.press(key);
      await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();
    }
    await page.keyboard.press("g");
    await page.keyboard.press("u");
    await expect(page.getByRole("heading", { level: 1, name: "Users" })).toBeVisible();
    await page.keyboard.press("/");
    await expect(page.locator("main form[role=search] input").first()).toBeFocused();
    // Typing is typing: a key that means something elsewhere just goes in the field.
    await page.keyboard.type("gi");
    await expect(page.getByRole("heading", { level: 1, name: "Users" })).toBeVisible();
    await page.locator("main form[role=search] input").first().blur();
    await page.keyboard.press("?");
    const panel = page.getByRole("dialog", { name: "Keys and help" });
    await expect(panel).toContainText("g then i");
    await expect(panel).toContainText("Next, previous row");
    await expect(panel.getByRole("link", { name: /For this screen/ })).toHaveAttribute(
      "href",
      /docs\/runbooks\/granting-credits\.md$/,
    );
    await expectAccessible(page);
    await page.keyboard.press("Escape");
    await expect(panel).toBeHidden();
  });

  test("a list's keys work in any list: next, previous and open", async ({ page }) => {
    await open(page, "/admin/users/");
    const table = page.getByRole("table", { name: "Users" });
    await expect(table.getByRole("row").nth(1)).toBeVisible();
    await page.keyboard.press("j");
    await expect(table.locator("tr[aria-current=true]")).toHaveCount(1);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/id=/);
  });

  test("a sheet traps focus, Esc closes it and focus goes back", async ({ page }) => {
    await open(page, "/admin/flags/");
    const trigger = page.getByRole("button", { name: /^Pause/ }).first();
    await trigger.focus();
    await page.keyboard.press("Enter");
    const sheet = page.getByRole("dialog");
    await expect(sheet).toBeVisible();
    for (let press = 0; press < 12; press += 1) {
      await page.keyboard.press("Tab");
      expect(await sheet.evaluate((element) => element.contains(document.activeElement))).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(sheet).toBeHidden();
    await expect(trigger).toBeFocused();
  });
});

/** Everything on the page a person could operate, as text for a report. */
const operable = `a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex="0"]:not([role=tablist])`;

test.describe("every screen", () => {
  for (const screen of SCREENS) {
    test(`${screen.label}: Tab reaches every control, shows focus, traps nothing, and no code goes unexplained`, async ({
      page,
    }, info) => {
      test.skip(onPhone(info), "Tab is a desktop's: the phone's checks are below");
      await open(page, screen.href);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await page.waitForLoadState("networkidle");
      const total = await page.locator(operable).evaluateAll(
        (found) =>
          found.filter((element) => {
            const box = element.getBoundingClientRect();
            return box.width > 0 && box.height > 0;
          }).length,
      );
      const reached = new Set<number>();
      const seenWithoutRing: string[] = [];
      await page.evaluate(() => {
        document
          .querySelectorAll("*")
          .forEach((element, index) => element.setAttribute("data-tabbed", String(index)));
      });
      let stuck = 0;
      let last = "";
      // A date field takes three stops of its own.
      const dates = await page.locator("input[type=date]").count();
      for (let press = 0; press < total + 5 + 3 * dates; press += 1) {
        await page.keyboard.press("Tab");
        const at = await page.evaluate(() => {
          const element = document.activeElement as HTMLElement | null;
          if (!element || element === document.body) {
            return null;
          }
          const style = getComputedStyle(element);
          return {
            id: element.getAttribute("data-tabbed") ?? "",
            date: element instanceof HTMLInputElement && element.type === "date",
            ring: style.outlineStyle !== "none" && style.outlineWidth !== "0px",
            what: `${element.tagName} ${element.getAttribute("aria-label") ?? element.textContent.slice(0, 40)}`,
          };
        });
        if (!at) {
          continue;
        }
        stuck = at.id === last && !at.date ? stuck + 1 : 0;
        expect(stuck, `focus stuck on ${at.what}`).toBeLessThan(2);
        last = at.id;
        reached.add(Number(at.id));
        // A date field shows its focus on the part being edited.
        if (!at.ring && !at.date) {
          seenWithoutRing.push(at.what);
        }
      }
      expect(seenWithoutRing, "focus isn't visible on these").toEqual([]);
      const unreached = await page.locator(operable).evaluateAll(
        (found, tabbed) =>
          found
            .filter((element) => {
              const box = element.getBoundingClientRect();
              return (
                box.width > 0 &&
                box.height > 0 &&
                !element.closest('[tabindex="-1"]') &&
                !tabbed.includes(Number(element.getAttribute("data-tabbed")))
              );
            })
            .map((element) => `${element.tagName} ${element.textContent.slice(0, 40)}`),
        [...reached],
      );
      expect(unreached, "Tab never reached these").toEqual([]);

      expect(await codesShown(page), "codes shown without words").toEqual([]);
    });
  }

  for (const screen of SCREENS) {
    test(`${screen.label}: axe reports nothing, on the screen or in any sheet it opens`, async ({
      page,
    }, info) => {
      test.skip(onPhone(info), "the phone's screens have their own checks");
      await open(page, screen.href);
      await page.waitForLoadState("networkidle");
      await expectAccessible(page);
      const names = await page
        .locator("main button:visible")
        .evaluateAll((buttons) => [...new Set(buttons.map((button) => button.textContent.trim()))]);
      for (const name of names.filter(Boolean).slice(0, 10)) {
        const button = page.locator("main button:visible").filter({ hasText: name }).first();
        if (!(await button.isEnabled().catch(() => false))) {
          continue;
        }
        await button.click({ timeout: 2_000 }).catch(() => undefined);
        const sheet = page.getByRole("dialog");
        if (await sheet.isVisible().catch(() => false)) {
          await expectAccessible(page);
          await page.keyboard.press("Escape");
          await expect(sheet).toBeHidden();
        }
      }
    });
  }
});

test.describe("the one table", () => {
  test("columns reorder, resize and pin, rows change density, and it's kept for this admin", async ({
    page,
  }, info) => {
    test.skip(onPhone(info), "a desktop's");
    const saved: unknown[] = [];
    await open(page, "/admin/users/", {
      "/admin/v1/table-settings/[^/]+": (_call, request) => {
        saved.push(request.postDataJSON());
        return { json: request.postDataJSON() as object };
      },
    });
    const table = page.getByRole("table", { name: "Users" });
    const first = () => table.getByRole("columnheader").nth(1);
    // The selection column arrives with the admin's permissions and shifts every heading's place by one: wait for it.
    await expect(table.getByRole("checkbox").first()).toBeVisible();
    const before = await first().innerText();
    const region = page.getByRole("region", { name: "Users" }).locator("..");
    await region.getByText("Columns", { exact: true }).click();
    const second = (await table.getByRole("columnheader").nth(2).innerText()).trim();
    const name = second.charAt(0) + second.slice(1).toLowerCase();
    await region.getByRole("button", { name: new RegExp(`Move ${name} earlier`, "i") }).click();
    await expect(first()).not.toHaveText(before);
    await region.getByRole("button", { name: new RegExp(`Pin ${name}`, "i") }).click();
    await expect(first()).toHaveCSS("position", "sticky");
    await region.getByRole("button", { name: new RegExp(`Make ${name} wider`, "i") }).click();
    await region.getByRole("button", { name: "Compact rows" }).click();
    await expect(region.getByRole("button", { name: "Compact rows" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await expect.poll(() => saved.length).toBeGreaterThanOrEqual(4);
    expect(saved.at(-1)).toMatchObject({ density: "compact", pinned: [expect.any(String)] });
    expect(Object.keys((saved.at(-1) as { widths: object }).widths)).toHaveLength(1);
  });

  test("the settings a person kept are what they find, and headers stay in view", async ({ page }, info) => {
    test.skip(onPhone(info), "a desktop's");
    await open(page, "/admin/users/", {
      "/admin/v1/table-settings": {
        json: { tables: { users: { order: [], widths: {}, pinned: ["Status"], density: "compact" } } },
      },
    });
    const table = page.getByRole("table", { name: "Users" });
    await expect(table.getByRole("columnheader").nth(1)).toContainText(/status/i);
    await expect(table.getByRole("columnheader").nth(1)).toHaveCSS("position", "sticky");
    await expect(table.getByRole("columnheader").nth(2)).toHaveCSS("top", "0px");
  });

  test("sorts by several columns: Shift adds one, and the server is asked for them all", async ({
    page,
  }, info) => {
    test.skip(onPhone(info), "a desktop's");
    await open(page, "/admin/sessions/");
    const asked = () =>
      page.waitForRequest(
        (request) =>
          new URL(request.url()).pathname === "/admin/v1/sessions" && request.url().includes("sort="),
      );
    const table = page.getByRole("table", { name: "Sessions" });
    let request = asked();
    await table.getByRole("button", { name: "State" }).click();
    expect(new URL((await request).url()).searchParams.get("sort")).toBe("state:desc");
    request = asked();
    await table.getByRole("button", { name: /Booked/ }).click({ modifiers: ["Shift"] });
    expect(new URL((await request).url()).searchParams.get("sort")).toBe("state:desc,createdAt:desc");
    await expect(table.getByRole("button", { name: /Booked/ })).toContainText("2");
    await expect(table.getByRole("columnheader", { name: /State/ })).toHaveAttribute(
      "aria-sort",
      "descending",
    );
  });

  test("an id is copied with one click", async ({ page, context }, info) => {
    test.skip(onPhone(info), "a desktop's");
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await open(page, "/admin/sessions/");
    await page
      .getByRole("button", { name: /^Copy the id/ })
      .first()
      .click();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toMatch(/^[0-9a-f-]{36}$/);
    await expect(page.getByRole("button", { name: "Copied" })).toBeVisible();
  });
});

test.describe("bulk from any list", () => {
  test("a list that allows it selects, previews, types the count and runs; one that doesn't has no boxes", async ({
    page,
  }, info) => {
    test.skip(onPhone(info), "a desktop's");
    const asked: unknown[] = [];
    await open(page, "/admin/users/", {
      "/admin/v1/queue/bulk/preview": (_call, request) => {
        asked.push(request.postDataJSON());
        return { json: admin.bulkPreviewOfAList };
      },
      "/admin/v1/queue/bulk": { json: admin.bulkRunning },
      [`/admin/v1/queue/bulk/${admin.bulkRunning.id}`]: { json: admin.bulkRunning },
    });
    await page
      .getByRole("checkbox", { name: /^Select this row/ })
      .first()
      .check();
    const bar = page.getByRole("region", { name: "1 selected" });
    await expect(bar.getByLabel("Do").locator("option")).toHaveText(["Tag", "Warn"]);
    await bar.getByLabel("Tag").fill("vip");
    await bar.getByRole("button", { name: "Preview" }).click();
    const sheet = page.getByRole("dialog");
    await expect(sheet).toBeVisible();
    expect(asked[0]).toMatchObject({ list: "users", action: { kind: "tag", tag: "vip" } });
    await expect(sheet).toContainText("not tagged → tagged");
    await sheet.getByLabel("Reason").fill("Tagging our best renters");
    await sheet.getByLabel(/to confirm$/).fill("1");
    await sheet.getByRole("button", { name: "Run it" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Finished" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Undo" })).toBeVisible();

    await page.keyboard.press("g");
    await page.keyboard.press("a");
    await expect(page.getByRole("heading", { level: 1, name: "Approvals" })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: /^Select this row/ })).toHaveCount(0);
  });
});

test.describe("on a phone, for an incident", () => {
  for (const [path, heading] of [
    ["/admin/inbox/", "Inbox"],
    ["/admin/approvals/", "Approvals"],
    ["/admin/flags/", "Flags"],
    ["/admin/", "Control room"],
  ] as const) {
    test(`${heading} fits at 390 px, nothing sideways, every control big enough`, async ({ page }, info) => {
      test.skip(!onPhone(info), "a phone's");
      await page.setViewportSize({ width: 390, height: 844 });
      await open(page, path);
      await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
      await page.waitForLoadState("networkidle");
      const sideways = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(sideways, "the page scrolls sideways by").toBeLessThanOrEqual(0);
      const small = await page
        .locator("main button:visible, main a:visible, main select:visible")
        .evaluateAll((found) =>
          found
            .filter((element) => {
              const box = element.getBoundingClientRect();
              return box.height > 0 && box.height < 24;
            })
            .map((element) => element.textContent.slice(0, 30)),
        );
      expect(small, "controls too small to tap").toEqual([]);
      await expectAccessible(page);
    });
  }

  test("the control room's health panel can be read on a phone", async ({ page }, info) => {
    test.skip(!onPhone(info), "a phone's");
    await page.setViewportSize({ width: 390, height: 844 });
    await open(page, "/admin/");
    const checks = page.getByRole("list", { name: "The control plane's checks" });
    await expect(checks).toBeVisible();
    const box = await checks.boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  });
});

test.describe("motion", () => {
  test("is respected: with reduced motion, a sheet appears without animating", async ({ page }, info) => {
    test.skip(onPhone(info), "a desktop's");
    await page.emulateMedia({ reducedMotion: "reduce" });
    await open(page, "/admin/flags/");
    await page
      .getByRole("button", { name: /^Pause/ })
      .first()
      .click();
    const name = await page
      .getByRole("dialog")
      .evaluate((element) => getComputedStyle(element).animationDuration);
    expect(Number.parseFloat(name)).toBeLessThanOrEqual(0.001);
  });
});
