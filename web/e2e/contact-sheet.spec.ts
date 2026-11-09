import { mkdirSync, readFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import { routes } from "./fixtures-admin-routes";
import * as support from "./fixtures-support";
import { API, fakeApi, signIn } from "./support";

/**
 * The console's contact sheet (ADR 0057), by hand only (.github/workflows/contact-sheet.yml): every screen in its main
 * states and both themes at 1440 px, and the phone-ready ones at 390 px, as pictures for a person to look through.
 * scripts/contact-sheet-index.ts then puts them on one page. It checks nothing the other specs don't.
 */
const OUT = process.env.CONTACT_SHEET_DIR ?? "contact-sheet";

const SCREENS = [
  ...readFileSync(new URL("../src/features/admin/AdminConsole.tsx", import.meta.url), "utf8").matchAll(
    /\{ href: "(\/admin\/[^"]*)", label: "([^"]+)"/g,
  ),
].map((match) => ({ href: match[1]!, label: match[2]! }));

/** A record's page is a state of its screen. */
const RECORDS = [
  { href: `/admin/users/?id=${admin.RENTER_ID}`, label: "Users", state: "a person" },
  { href: `/admin/hosts/?id=${admin.HOST_ID}`, label: "Hosts", state: "a host" },
  { href: `/admin/sessions/?id=${admin.SESSION_ID}`, label: "Sessions", state: "a session" },
  { href: `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`, label: "Abuse", state: "a review" },
  { href: `/admin/appeals/?id=${admin.appeal.id}`, label: "Appeals", state: "an appeal" },
  { href: `/admin/tickets/?id=${support.PRIVACY_TICKET}`, label: "Tickets", state: "a ticket" },
  { href: `/admin/payouts/?batch=${admin.BATCH_ID}`, label: "Payouts", state: "a batch" },
];

/** What alerts reach a phone for (ADR 0057). */
const PHONE_READY = ["/admin/inbox/", "/admin/approvals/", "/admin/flags/", "/admin/"];

/** What the frame itself asks for: left alone, so a screen's own loading and error states are what shows. */
const FRAME = /\/admin\/v1\/(session|table-settings|queue\/bulk\/lists|queue\/counts|approvals\/count)/;

type Answering = "ok" | "loading" | "error";

async function open(page: Page, href: string, answering: Answering): Promise<void> {
  await fakeApi(page, routes({ "/admin/v1/views": { json: { views: [] } } }));
  if (answering !== "ok") {
    await page.route(`${API}/admin/v1/**`, async (route) => {
      const asked = route.request();
      if (asked.method() !== "GET" || FRAME.test(new URL(asked.url()).pathname)) {
        await route.fallback();
      } else if (answering === "error") {
        await route.fulfill({
          status: 503,
          contentType: "application/problem+json",
          headers: { "Access-Control-Allow-Origin": asked.headers().origin ?? "*" },
          body: JSON.stringify({ status: 503, title: "Error", detail: "A fixture error." }),
        });
      }
      // Loading: never answered.
    });
  }
  await signIn(page, "asha", href);
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
}

const fileName = (label: string, state: string, theme: string, width: number) =>
  `${OUT}/${label.toLowerCase().replaceAll(" ", "-")}--${state.replaceAll(" ", "-")}--${theme}--${width}.png`;

async function picture(
  page: Page,
  label: string,
  state: string,
  theme: string,
  settle: number,
): Promise<void> {
  await page.waitForTimeout(settle);
  const width = page.viewportSize()?.width ?? 1440;
  await page.screenshot({
    path: fileName(label, state, theme, width),
    fullPage: true,
    animations: "disabled",
  });
}

test.beforeAll(() => {
  mkdirSync(OUT, { recursive: true });
});

for (const theme of ["dark", "light"] as const) {
  test.describe(theme, () => {
    test.use({ colorScheme: theme });
    test.beforeEach(async ({ page }) => {
      await page.clock.setFixedTime(data.NOW);
    });

    for (const screen of SCREENS) {
      for (const [state, answering, settle] of [
        ["main", "ok", 800],
        ["loading", "loading", 400],
        ["cant-load", "error", 8_000],
      ] as const) {
        test(`${screen.label}, ${state}, 1440`, async ({ page }) => {
          await page.setViewportSize({ width: 1440, height: 900 });
          await open(page, screen.href, answering);
          await picture(page, screen.label, state, theme, settle);
        });
      }
    }

    for (const record of RECORDS) {
      test(`${record.label}, ${record.state}, 1440`, async ({ page }) => {
        await page.setViewportSize({ width: 1440, height: 900 });
        await open(page, record.href, "ok");
        await picture(page, record.label, record.state, theme, 800);
      });
    }

    for (const href of PHONE_READY) {
      const label = SCREENS.find((screen) => screen.href === href)!.label;
      test(`${label}, 390`, async ({ page }) => {
        await page.setViewportSize({ width: 390, height: 844 });
        await open(page, href, "ok");
        await picture(page, label, "main", theme, 800);
      });
    }

    test("a sheet open, and the keys panel, 1440", async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await open(page, "/admin/flags/", "ok");
      await page
        .getByRole("button", { name: /^Pause/ })
        .first()
        .click();
      await picture(page, "Flags", "a sheet", theme, 400);
      await page.keyboard.press("Escape");
      await expect(page.getByRole("dialog")).toBeHidden();
      await page.keyboard.press("?");
      await picture(page, "Flags", "keys and help", theme, 400);
    });
  });
}
