import { expect, test, type Page } from "@playwright/test";
import { writeFileSync } from "node:fs";
import * as credit from "./fixtures-credit";
import * as data from "./fixtures";
import * as publicData from "./fixtures-public";
import { hostingApi } from "./hosting-api";
import { HOST_URL } from "./sites";
import { fakeApi, signIn, type Answer } from "./support";

/**
 * "Full" (ADR 0058): at 1440 x 900, the first screen of every main page of both sites holds real content, with no large
 * empty area. The check is simple on purpose: a grid of points over the first screen beside the sidebar, each counted as
 * content when what's under it is a tile, a heading, text or a control, and empty when it's only the page's background
 * between them. A page fails under 60%. Every page's figure goes to the log (and E2E_FULL_REPORT, for the run's summary).
 */
const LEAST = 0.6;
const COLUMNS = 36;
const ROWS = 22;

const renter = (): Record<string, Answer> => ({
  "/v1/me": { json: data.me() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/wallet/transactions": { json: data.history(0) },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  "/v1/uploads": { json: data.uploadsPage() },
  "/v1/availability": { json: data.availability },
  "/v1/usage": { json: data.usageOf(data.asha.sessions) },
  "/v1/public/availability": { json: publicData.availability() },
  "/v1/wallet/billing-details": { json: credit.billingDetails },
  "/v1/wallet/invoices": { json: credit.invoices },
  "/v1/wallet/refund-requests": { json: credit.noRefunds },
});

/**
 * `asserted: false`: measured and reported, not held to the bar (ADR 0058): Book's first step is a short chooser by
 * design, and My files is as long as what a renter keeps, four uploads in these fixtures.
 */
const PAGES: { site: "app" | "host"; path: string; name: string; ready: string; asserted?: false }[] = [
  { site: "app", path: "/", name: "renter home", ready: "What do you want to run?" },
  { site: "app", path: "/book/", name: "book", ready: "Fine-tune a model", asserted: false },
  { site: "app", path: "/sessions/", name: "sessions", ready: "Sessions" },
  { site: "app", path: "/files/", name: "files", ready: "My files", asserted: false },
  { site: "app", path: "/credit/", name: "credit", ready: "Credit" },
  { site: "host", path: "/", name: "host home", ready: "Demand for your GPU" },
  { site: "host", path: "/laptops/", name: "laptops", ready: "Add a laptop" },
  { site: "host", path: "/earnings/", name: "earnings", ready: "Hours rented" },
  { site: "host", path: "/payouts/", name: "payouts", ready: "Next payout" },
  { site: "host", path: "/help/", name: "host help", ready: "The Windows app" },
];

/** The share of the first screen beside the sidebar that's content, by the rule above. */
async function fullness(page: Page): Promise<number> {
  return page.evaluate(
    ({ columns, rows }) => {
      const sidebar = document.querySelector("aside")?.getBoundingClientRect().right ?? 0;
      const width = window.innerWidth - sidebar;
      const height = window.innerHeight;
      const isContent = (start: Element | null): boolean => {
        for (let at = start; at && at !== document.body; at = at.parentElement) {
          if (at.tagName === "MAIN" || at.id === "root") {
            return false;
          }
          const style = getComputedStyle(at);
          const painted =
            style.backgroundColor !== "rgba(0, 0, 0, 0)" && style.backgroundColor !== "transparent";
          if (at.hasAttribute("data-tile") || painted) {
            return true;
          }
          if (
            /^(H[1-6]|P|A|BUTTON|LI|TABLE|TR|TD|TH|INPUT|LABEL|SVG|SPAN|CODE|PRE|DL|DT|DD)$/.test(at.tagName)
          ) {
            return true;
          }
        }
        return false;
      };
      let filled = 0;
      for (let row = 0; row < rows; row++) {
        for (let column = 0; column < columns; column++) {
          const x = sidebar + ((column + 0.5) * width) / columns;
          const y = ((row + 0.5) * height) / rows;
          if (isContent(document.elementFromPoint(x, y))) {
            filled += 1;
          }
        }
      }
      return filled / (columns * rows);
    },
    { columns: COLUMNS, rows: ROWS },
  );
}

const figures: Record<string, number> = {};

for (const screen of PAGES) {
  test(`${screen.name} is full at 1440 x 900`, async ({ page }, info) => {
    test.skip(info.project.name !== "screens-laptop-light", "measured once, at the laptop's size");
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.clock.setFixedTime(data.NOW);
    await fakeApi(page, { ...renter(), ...(screen.site === "host" ? hostingApi() : {}) });
    const origin = screen.site === "host" ? HOST_URL : "";
    await signIn(page, "asha", screen.path, origin);
    await expect(page.getByText(screen.ready).first()).toBeVisible({ timeout: 10_000 });
    // Once what arrives after the paint has.
    await page.waitForTimeout(1500);
    const share = await fullness(page);
    figures[`${screen.site} ${screen.path}`] = Math.round(share * 100);
    console.log(
      `full: ${screen.site} ${screen.path} (${screen.name}) ${Math.round(share * 100)}% of the first screen`,
    );
    if (screen.asserted !== false) {
      expect(
        share,
        `${screen.name}: ${Math.round(share * 100)}% of the first screen is content`,
      ).toBeGreaterThanOrEqual(LEAST);
    }
  });
}

test.afterAll(() => {
  const path = process.env.E2E_FULL_REPORT;
  if (path && Object.keys(figures).length > 0) {
    writeFileSync(path, `${JSON.stringify(figures, null, 2)}\n`);
  }
});
