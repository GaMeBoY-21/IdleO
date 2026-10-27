// Saves every screen as a picture, for the product owner to review: each at 1440 and 390 px wide, in both themes, into
// private/screens/<date>/<group>/ (git-ignored). The website's pages come from its sitemap, so a new page is in it
// without being listed here; the API answers from the same invented figures the browser tests use.
//
//   pnpm screenshots                 # builds nothing: run `pnpm build:site` first (IDLEO_SITE_URL=http://localhost:3002 ...)
//   SHOTS_DATE=2026-10-27 pnpm screenshots
//
// The groups are added as each part of the product is redrawn; today there is `site`.
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { chromium, type Browser, type Page } from "@playwright/test";
import { availability } from "./fixtures-public.ts";

const web = fileURLToPath(new URL("../", import.meta.url));
const repo = fileURLToPath(new URL("../../", import.meta.url));
const date = process.env.SHOTS_DATE ?? new Date().toISOString().slice(0, 10);
const out = (group: string) => {
  const directory = `${repo}private/screens/${date}/${group}`;
  mkdirSync(directory, { recursive: true });
  return directory;
};

/** The two widths (a laptop and a phone) and the two themes every screen is saved in. */
const WIDTHS = [
  { name: "1440", viewport: { width: 1440, height: 900 }, scale: 1, mobile: false },
  { name: "390", viewport: { width: 390, height: 844 }, scale: 2, mobile: true },
] as const;
const THEMES = ["dark", "light"] as const;

const contact = {
  topics: [
    { code: "session", label: "A session" },
    { code: "money", label: "Money and credit" },
    { code: "hosting", label: "Hosting" },
    { code: "account", label: "My account" },
    { code: "privacy", label: "A privacy request" },
    { code: "report", label: "Report a problem with a host or renter" },
    { code: "grievance", label: "A grievance" },
  ],
  grievanceOfficer: {
    name: "Grievance Officer (development placeholder)",
    email: "grievance@example.invalid",
    postalAddress: "Not a real address: development only",
  },
  responseDays: 30,
};

/** The website, served from its build the way a host would (scripts/serve.ts). */
async function serveSite(): Promise<{ url: string; stop: () => void }> {
  const port = process.env.SITE_PORT ?? "3002";
  const server = spawn("node", ["scripts/serve.ts"], {
    cwd: web,
    env: { ...process.env, IDLEO_DIST: process.env.IDLEO_WEBSITE_DIST ?? "dist-website", PORT: port },
    stdio: "ignore",
  });
  const url = `http://127.0.0.1:${port}`;
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      if ((await fetch(`${url}/robots.txt`)).ok) {
        return { url, stop: () => server.kill("SIGTERM") };
      }
    } catch {
      // Not up yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  server.kill("SIGTERM");
  throw new Error(`the website did not start on port ${port}`);
}

function sitemapPaths(): string[] {
  const dist = `${web}${process.env.IDLEO_WEBSITE_DIST ?? "dist-website"}/`;
  const xml = readFileSync(`${dist}sitemap.xml`, "utf8");
  return [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => new URL(match[1]!).pathname);
}

const nameOf = (path: string) =>
  path === "/" ? "home" : path.replaceAll("/", " ").trim().replaceAll(" ", "-");

async function settle(page: Page, path: string): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  if (path === "/" || path === "/pricing/") {
    // The live prices have arrived, so the picture is of the settled page.
    await page.getByText("Prices as of").waitFor({ timeout: 10_000 });
  }
  if (path === "/contact/" || path === "/privacy/") {
    await page.getByText("grievance@example.invalid").waitFor({ timeout: 10_000 });
  }
}

async function website(browser: Browser): Promise<number> {
  const site = await serveSite();
  const directory = out("site");
  const paths = sitemapPaths();
  let saved = 0;
  try {
    for (const theme of THEMES) {
      for (const width of WIDTHS) {
        const context = await browser.newContext({
          viewport: width.viewport,
          deviceScaleFactor: width.scale,
          isMobile: width.mobile,
          hasTouch: width.mobile,
          colorScheme: theme,
          locale: "en-IN",
          timezoneId: "Asia/Kolkata",
          reducedMotion: "reduce",
        });
        await context.route("**/v1/public/availability", (route) =>
          route.fulfill({ json: availability(10_000) }),
        );
        await context.route("**/v1/public/contact", (route) => route.fulfill({ json: contact }));
        // The website's API address is the build's; whatever it is, the pictures show the same figures.
        const page = await context.newPage();
        for (const path of paths) {
          await page.goto(`${site.url}${path}`);
          await settle(page, path);
          await page.screenshot({
            path: `${directory}/${nameOf(path)}--${width.name}-${theme}.png`,
            fullPage: true,
          });
          saved += 1;
        }
        await context.close();
      }
    }
  } finally {
    site.stop();
  }
  return saved;
}

const browser = await chromium.launch();
try {
  const saved = await website(browser);
  console.log(`screenshots: ${saved} pictures of the website in private/screens/${date}/site/`);
} finally {
  await browser.close();
}
