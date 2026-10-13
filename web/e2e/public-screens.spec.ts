import { expect, test, type Page } from "@playwright/test";
import { availability, nobodyOnline } from "./fixtures-public";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, recordLayoutShifts, watchConsole } from "./support";

/**
 * The public pages (ADR 0029), signed out: every page with zero axe violations and no console errors, nothing moving
 * as the live figures arrive, the pricing states (loading, loaded, nobody online, error with retry, stale), the earnings
 * calculator, no app code loaded, the way from the landing page into the app, and the sitemap. Phone and laptop, light
 * and dark. The API answers from fixtures.
 */
const PAGES = [
  "/about/",
  "/pricing/",
  "/gpus/",
  "/earn/",
  "/docs/",
  "/docs/services/",
  "/docs/cli/",
  "/docs/api/",
  "/docs/hosting/",
  "/docs/faq/",
  "/terms/",
  "/privacy/",
  "/contact/",
];

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(new Date("2026-10-13T10:05:00Z"));
});
test.afterEach(() => finish());

for (const path of PAGES) {
  test(`${path} is accessible, readable at this width, and has its page's tags`, async ({ page }, info) => {
    await fakeApi(page, { "/v1/public/availability": { json: availability(10_000) } });
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    // The live figures have arrived, so axe sees the settled page.
    if (path === "/pricing/") {
      await expect(page.getByText("Prices as of")).toBeVisible();
    }
    await expectAccessible(page);
    await capture(page, info, `public-${path.replaceAll("/", "-").replace(/^-|-$/g, "")}`);
    await expect(page).toHaveTitle(/.{10,}/);
    for (const name of ["description", "og:title", "og:description"]) {
      const tag = page.locator(`meta[name="${name}"], meta[property="${name}"]`);
      await expect(tag, name).toHaveAttribute("content", /.{10,}/);
    }
    await expect(page.locator('link[rel="canonical"]')).toHaveAttribute("href", /\/$/);
  });
}

test.describe("what loads", () => {
  test("a page with nothing live loads no script of the app, and calls no API", async ({ page }) => {
    const requests: string[] = [];
    page.on("request", (request) => requests.push(new URL(request.url()).pathname));
    await page.goto("/about/");
    await page.waitForLoadState("networkidle");
    expect(requests.filter((path) => path.endsWith(".js"))).toEqual([]);
    expect(requests.filter((path) => path.startsWith("/assets/"))).toEqual([]);
    expect(requests.filter((path) => path.startsWith("/v1/"))).toEqual([]);
  });

  test("pricing loads its own two small scripts and one call, nothing of the app", async ({ page }) => {
    await fakeApi(page, { "/v1/public/availability": { json: availability() } });
    const requests: string[] = [];
    page.on("request", (request) => requests.push(new URL(request.url()).pathname));
    await page.goto("/pricing/");
    await expect(page.getByText("Prices as of")).toBeVisible();
    expect(
      requests.filter((path) => path.endsWith(".js")).every((path) => path.startsWith("/site-assets/")),
    ).toBe(true);
    expect(requests.filter((path) => path.startsWith("/assets/"))).toEqual([]);
    expect(requests.filter((path) => path.startsWith("/v1/"))).toEqual(["/v1/public/availability"]);
  });

  test("the sitemap lists every public page and robots.txt points at it", async ({ request }) => {
    const sitemap = await (await request.get("/sitemap.xml")).text();
    for (const path of PAGES) {
      expect(sitemap).toContain(`${path}</loc>`);
    }
    expect(await (await request.get("/robots.txt")).text()).toMatch(/Sitemap: .*\/sitemap\.xml/);
  });
});

test.describe("pricing", () => {
  test("holds its place while waiting, then shows live and typical ranges, said to be so", async ({
    page,
  }) => {
    await fakeApi(page, { "/v1/public/availability": "hold" });
    await page.goto("/pricing/");
    await expect(page.getByText("Looking up today's prices…")).toBeVisible();
    const row = page.locator('[data-class-row="12"]');
    const waiting = await row.boundingBox();
    await expect(row.locator('[data-role="skeleton"]')).toBeVisible();

    await page.unrouteAll();
    await fakeApi(page, { "/v1/public/availability": { json: availability(10_000) } });
    await page.reload();
    await expect(row.getByText("₹7.20–10.80 an hour")).toBeVisible();
    await expect(row.getByText("Right now, across the hosts online")).toBeVisible();
    expect(await row.boundingBox()).toEqual(waiting);
    // Under three hosts, only the typical range, and it says so.
    const eight = page.locator('[data-class-row="8"]');
    await expect(eight.getByText("₹4.80–8.40 an hour")).toBeVisible();
    await expect(eight.getByText(/^Typical/)).toBeVisible();
    await expect(page.getByText("New accounts start with ₹100.00 of credit.")).toBeVisible();
  });

  test("works the examples from the catalog's minutes at the 12 GB class's range", async ({ page }) => {
    await fakeApi(page, { "/v1/public/availability": { json: availability() } });
    await page.goto("/pricing/");
    const quick = page.locator("[data-example]").first();
    // 15 minutes at 12 to 18 paise a minute.
    await expect(quick.getByText("₹1.80–2.70, typical")).toBeVisible();
    await expect(page.locator("[data-example]").nth(1).getByText("₹5.40–8.10, typical")).toBeVisible();
  });

  test("with no host online says so for every class and shows the typical ranges", async ({ page }) => {
    await fakeApi(page, { "/v1/public/availability": { json: nobodyOnline() } });
    await page.goto("/pricing/");
    await expect(page.getByText("None online now")).toHaveCount(4);
    await expect(page.getByText("₹9.60–16.80 an hour")).toBeVisible();
    await expect(page.locator("[data-class-row]").getByText(/^Typical/)).toHaveCount(4);
    await expectAccessible(page);
  });

  test("says what happened when it can't load, and tries again", async ({ page }, info) => {
    await fakeApi(page, {
      "/v1/public/availability": (call) => (call === 1 ? { status: 503 } : { json: availability() }),
    });
    await page.goto("/pricing/");
    await expect(
      page.getByText("We couldn't load today's prices. Check your connection and try again."),
    ).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "public-pricing-error");
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("Prices as of")).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeHidden();
  });

  test("keeps the last figures and says when a refresh fails", async ({ page }) => {
    await page.clock.install({ time: new Date("2026-10-13T10:05:00Z") });
    await fakeApi(page, {
      "/v1/public/availability": (call) => (call === 1 ? { json: availability() } : { status: 503 }),
    });
    await page.goto("/pricing/");
    await expect(page.getByText("Prices as of")).toBeVisible();
    await page.clock.runFor(61_000);
    await expect(page.getByText(/Couldn't refresh\. Showing prices from/)).toBeVisible();
    await expect(page.locator('[data-class-row="12"]').getByText("₹7.20–10.80 an hour")).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  for (const path of ["/pricing/", "/earn/"]) {
    test(`${path} doesn't move while its figures arrive`, async ({ page }) => {
      await fakeApi(page, { "/v1/public/availability": { json: availability(10_000) } }, { delayMs: 400 });
      const shifts = await recordLayoutShifts(page);
      await page.goto(path);
      await page.waitForTimeout(1500);
      await page.waitForFunction(() => document.fonts.status === "loaded");
      const { total, sources } = await shifts();
      expect(total, `layout shifted by ${total}: ${sources.join(" | ")}`).toBe(0);
    });
  }
});

test.describe("the earnings calculator", () => {
  test("starts from the range for the chosen GPU and works the figures from whole paise", async ({
    page,
  }, info) => {
    await fakeApi(page, { "/v1/public/availability": { json: availability() } });
    await page.goto("/earn/");
    // 12 GB: the middle of ₹7.20–10.80, then 8 hours free, 20% rented, 80% kept.
    await expect(page.getByLabel("Your price, ₹ an hour")).toHaveValue("9.00");
    await expect(page.getByText("Hosts with a GPU this size ask: ₹7.20–10.80 an hour")).toBeVisible();
    await expect(page.locator('[data-role="per-day"]')).toHaveText("₹11.52");
    await expect(page.locator('[data-role="per-month"]')).toHaveText("₹345.60");
    await expect(page.getByText("Occupancy depends on demand; early on it may be low.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "public-earn-calculated");

    await page.getByLabel("Share of those hours that get rented").selectOption("50");
    await expect(page.locator('[data-role="per-day"]')).toHaveText("₹28.80");
    await page.getByLabel("Hours a day your machine is free to rent").fill("24");
    await expect(page.locator('[data-role="per-day"]')).toHaveText("₹86.40");
  });

  test("keeps a price the person typed when the GPU changes, and says when an input can't be used", async ({
    page,
  }) => {
    await fakeApi(page, { "/v1/public/availability": { json: availability() } });
    await page.goto("/earn/");
    await expect(page.getByLabel("Your price, ₹ an hour")).toHaveValue("9.00");
    await page.getByLabel("Your price, ₹ an hour").fill("6.60");
    await page.getByLabel("Your GPU's memory").selectOption("8");
    await expect(page.getByLabel("Your price, ₹ an hour")).toHaveValue("6.60");
    await expect(page.getByText("Typically asked for a GPU this size: ₹4.80–8.40 an hour")).toBeVisible();
    await page.getByLabel("Your price, ₹ an hour").fill("six");
    await expect(page.locator('[data-role="per-day"]')).toHaveText("–");
    await expect(
      page.getByText("Enter a price, 1 to 24 hours, and a share to see an estimate."),
    ).toBeVisible();
  });
});

async function homeIsServed(page: Page) {
  await fakeApi(page, {
    "/v1/me": { json: data.me(), etag: '"3"' },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  });
}

test.describe("the way into the app", () => {
  test("landing, Get started, sign in, and the signed-in Home", async ({ page }) => {
    await homeIsServed(page);
    await page.goto("/about/");
    await page.getByRole("link", { name: "Get started" }).first().click();
    await expect(page).toHaveURL(/\/sign-in\/$/);
    await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
    await page.getByRole("button", { name: "Email me a sign-in link" }).click();
    await page.waitForURL((url) => url.pathname === "/");
    await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();
  });

  test("Start hosting goes to sign-in and then to Hosting", async ({ page }) => {
    await homeIsServed(page);
    await page.goto("/earn/");
    await page.getByRole("link", { name: "Start hosting" }).first().click();
    await expect(page).toHaveURL(/\/sign-in\/\?next=%2Fhosting%2F$/);
  });

  test("sign-in links to the terms and privacy pages", async ({ page }) => {
    await page.goto("/sign-in/");
    await page.getByRole("link", { name: "terms" }).click();
    await expect(page).toHaveURL(/\/terms\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "Terms" })).toBeVisible();
    await expect(page.getByText("Being drafted.")).toBeVisible();
  });
});
