import type { Page, TestInfo } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { audit, type Problem } from "./ui-audit";

/**
 * One picture of the UI review (web/e2e/ui-review.ts): the screen as drawn, at most 4,000 px of it, and what
 * e2e/ui-audit.ts found on it, side by side in UI_REVIEW_DIR/raw/<site>/. The sweep (e2e/ui-review.spec.ts) calls it for
 * every page in every state; e2e/support.ts's capture() for every state a screen's own spec draws.
 */
export type ReviewSite = "app" | "host" | "admin" | "website";

export type Capture = {
  site: ReviewSite;
  screen: string;
  path: string;
  state: string;
  width: number;
  theme: "dark" | "light";
  title: string;
  file: string;
  problems: Problem[];
};

const TALLEST = 4000;

/** The project's theme, from its name (`ui-review-<width>-<theme>`, `ui-review-phone-390-<theme>`). */
export const themeOf = (info: TestInfo): "dark" | "light" =>
  info.project.name.endsWith("light") ? "light" : "dark";

/** Which site a page is on, by its origin (e2e/sites.ts). */
export function siteOf(url: string): ReviewSite {
  const origin = new URL(url).origin;
  const sites: [string | undefined, ReviewSite][] = [
    [process.env.HOST_URL ?? "http://localhost:3003", "host"],
    [process.env.ADMIN_URL ?? "http://localhost:3004", "admin"],
    [process.env.SITE_URL ?? "http://localhost:3002", "website"],
  ];
  return sites.find(([address]) => address && new URL(address).origin === origin)?.[1] ?? "app";
}

const slug = (value: string) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 90) || "home";

export async function review(
  page: Page,
  info: TestInfo,
  given: { site: ReviewSite; screen: string; path: string; state: string; shifts?: number; axe?: boolean },
): Promise<void> {
  const dir = process.env.UI_REVIEW_DIR;
  if (!dir) {
    return;
  }
  await page.evaluate(() => document.fonts.ready);
  const width = page.viewportSize()?.width ?? 1440;
  const theme = themeOf(info);
  const problems = await audit(page, {
    phone: width < 768,
    axe: given.axe ?? false,
    shifts: given.shifts,
  }).catch((error: unknown) => [
    { rule: "audit-failed", severity: "P2" as const, detail: String(error).slice(0, 200), count: 1 },
  ]);
  const name = `${slug(given.screen)}--${slug(given.state)}--${width}--${theme}`;
  const folder = `${dir}/raw/${given.site}`;
  mkdirSync(folder, { recursive: true });
  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.screenshot({
    path: `${folder}/${name}.jpg`,
    type: "jpeg",
    quality: 70,
    fullPage: true,
    clip: { x: 0, y: 0, width, height: Math.min(height, TALLEST) },
    animations: "disabled",
    caret: "hide",
  });
  const capture: Capture = {
    site: given.site,
    screen: given.screen,
    path: given.path,
    state: given.state,
    width,
    theme,
    title: await page.title(),
    file: `${name}.jpg`,
    problems,
  };
  writeFileSync(`${folder}/${name}.json`, JSON.stringify(capture));
}
