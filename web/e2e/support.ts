import AxeBuilder from "@axe-core/playwright";
import { expect, type Page, type TestInfo } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const API = process.env.API_URL ?? "http://localhost:8080";

/** Signs in through the web app's own sign-in screen, as an invented @example.com user (the local adapter). */
export async function signIn(page: Page, user: string, path = "/"): Promise<void> {
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill(`${user}@example.com`);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/sign-in"));
  if (path !== "/" || new URL(page.url()).pathname !== "/") {
    await page.goto(path);
  }
}

/** How the fake API answers one path: data, a status with a problem detail, or nothing (held, to show loading). */
export type Answer =
  | { json: unknown }
  | { status: number }
  | "hold"
  | ((call: number) => { json: unknown } | { status: number });

/** Answers the API from fixtures: the first route whose path matches wins; anything else is a 404 problem. */
export async function fakeApi(
  page: Page,
  routes: Record<string, Answer>,
  { delayMs = 0 } = {},
): Promise<void> {
  const calls = new Map<string, number>();
  await page.route(`${API}/v1/**`, async (route) => {
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    const url = new URL(route.request().url());
    const origin = route.request().headers().origin ?? "http://localhost:3000";
    const cors = () => ({
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Headers": "authorization,content-type",
      "Access-Control-Expose-Headers": "ETag, Location",
    });
    if (route.request().method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: cors() });
      return;
    }
    const key = Object.keys(routes).find((pattern) => new RegExp(`^${pattern}$`).test(url.pathname));
    let answer: Answer | undefined = key ? routes[key] : undefined;
    if (answer === "hold") {
      return;
    }
    if (typeof answer === "function") {
      const call = (calls.get(key!) ?? 0) + 1;
      calls.set(key!, call);
      answer = answer(call);
    }
    if (answer && "json" in answer) {
      await route.fulfill({ status: 200, json: answer.json, headers: cors() });
    } else {
      const status = answer && "status" in answer ? answer.status : 404;
      await route.fulfill({
        status,
        contentType: "application/problem+json",
        body: JSON.stringify({ status, title: "Error", detail: "A fixture error." }),
        headers: cors(),
      });
    }
  });
}

/** Zero axe violations, WCAG 2.2 A and AA (ADR 0018). */
export async function expectAccessible(page: Page): Promise<void> {
  // Contrast is measured on the settled screen, not halfway through a fade-in.
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every(
        (animation) =>
          animation.effect?.getTiming().iterations === Infinity || animation.playState !== "running",
      ),
  );
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    results.violations.map(
      (violation) => `${violation.id}: ${violation.nodes.map((node) => node.target).join(", ")}`,
    ),
  ).toEqual([]);
}

const FULL_PAGE_STYLE = fileURLToPath(new URL("full-page.css", import.meta.url));

/** Records every layout shift the page makes, from its first paint (web/DESIGN.md "Motion": CLS 0). */
export async function recordLayoutShifts(
  page: Page,
): Promise<() => Promise<{ total: number; sources: string[] }>> {
  await page.addInitScript(() => {
    const shifts: { value: number; sources: string[] }[] = [];
    (window as unknown as { __shifts: typeof shifts }).__shifts = shifts;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as unknown as {
        value: number;
        hadRecentInput: boolean;
        sources?: { node?: Node | null; previousRect: DOMRectReadOnly; currentRect: DOMRectReadOnly }[];
      }[]) {
        if (!entry.hadRecentInput) {
          shifts.push({
            value: entry.value,
            sources: (entry.sources ?? []).map((source) => {
              const what =
                source.node instanceof Element
                  ? source.node.outerHTML.slice(0, 90)
                  : String(source.node?.nodeName);
              const rect = (r: DOMRectReadOnly) =>
                `${Math.round(r.x)},${Math.round(r.y)} ${Math.round(r.width)}x${Math.round(r.height)}`;
              return `${what} [${rect(source.previousRect)} -> ${rect(source.currentRect)}]`;
            }),
          });
        }
      }
    }).observe({ type: "layout-shift", buffered: true });
  });
  return () =>
    page.evaluate(() => {
      const shifts = (window as unknown as { __shifts: { value: number; sources: string[] }[] }).__shifts;
      return {
        total: shifts.reduce((sum, shift) => sum + shift.value, 0),
        sources: shifts.flatMap((shift) => shift.sources),
      };
    });
}

/** The screen as it is now: compared with its visual baseline, and copied into the review gallery when asked. */
export async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  // No screen is cramped or broken at either size (web/DESIGN.md): nothing scrolls sideways.
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, `${name} scrolls sideways by ${overflow} px`).toBeLessThanOrEqual(0);
  const gallery = process.env.GALLERY_DIR;
  if (gallery) {
    mkdirSync(gallery, { recursive: true });
    await page.screenshot({
      path: `${gallery}/${name}--${testInfo.project.name.replace("screens-", "")}.png`,
      fullPage: true,
      style: readFileSync(FULL_PAGE_STYLE, "utf8"),
    });
  }
  if (!process.env.SKIP_VISUAL) {
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true, stylePath: FULL_PAGE_STYLE });
  }
}
