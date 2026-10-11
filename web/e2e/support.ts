import AxeBuilder from "@axe-core/playwright";
import { expect, type Page, type Request as PlaywrightRequest, type TestInfo } from "@playwright/test";
import { copyFileSync, mkdirSync, readFileSync } from "node:fs";
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
  | { json: unknown; status?: number; etag?: string }
  | { status: number; problem?: string; errors?: { field: string; detail: string }[] }
  | { events: string }
  | "hold"
  | ((
      call: number,
      request: PlaywrightRequest,
    ) =>
      | { json: unknown; status?: number; etag?: string }
      | { status: number; problem?: string }
      | { events: string }
      | "hold");

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
      "Access-Control-Allow-Headers": "authorization,content-type,idempotency-key,accept",
      "Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE",
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
      answer = answer(call, route.request());
      if (answer === "hold") {
        return;
      }
    }
    if (answer && "json" in answer) {
      await route.fulfill({
        status: answer.status ?? 200,
        json: answer.json,
        headers: { ...cors(), ...(answer.etag ? { ETag: answer.etag } : {}) },
      });
    } else if (answer && "events" in answer) {
      await route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: answer.events,
        headers: cors(),
      });
    } else {
      const status = answer && "status" in answer ? answer.status : 404;
      const problem =
        answer && "problem" in answer ? answer.problem : status === 404 ? "not-found" : undefined;
      await route.fulfill({
        status,
        contentType: "application/problem+json",
        body: JSON.stringify({
          ...(problem ? { type: `/problems/${problem}` } : {}),
          status,
          title: "Error",
          detail: "A fixture error.",
          ...(answer && "errors" in answer ? { errors: answer.errors } : {}),
        }),
        headers: cors(),
      });
    }
  });
}

/**
 * Answers storage's part uploads (the presigned PUTs to MinIO) with an ETag, as MinIO does; `delayMs` slows each
 * part so progress can be seen, and `hold` keeps them waiting.
 */
export async function fakeStorage(page: Page, { delayMs = 0, hold = false } = {}): Promise<void> {
  await page.route("http://localhost:9100/**", async (route) => {
    const cors = {
      "Access-Control-Allow-Origin": route.request().headers().origin ?? "http://localhost:3000",
      "Access-Control-Allow-Methods": "PUT",
      "Access-Control-Allow-Headers": "content-type",
      "Access-Control-Expose-Headers": "ETag",
    };
    if (route.request().method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: cors });
      return;
    }
    if (hold) {
      return;
    }
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    await route.fulfill({
      status: 200,
      headers: { ...cors, ETag: `"${route.request().url().split("/").pop()}"` },
    });
  });
}

/**
 * Fails the test on any console error or uncaught exception: hydration mismatches and uncaught errors show here; a
 * failed fixture request is expected in the error states.
 */
export function watchConsole(page: Page): () => void {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource")) {
      errors.push(message.text());
    }
  });
  page.on("pageerror", (error) => errors.push(error.message));
  return () => expect(errors, "console errors").toEqual([]);
}

/** Zero axe violations, WCAG 2.2 A and AA (ADR 0018). */
export async function expectAccessible(page: Page): Promise<void> {
  // Contrast is measured on the settled screen, not halfway through a fade-in. Two frames first, so an animation
  // that something just opened has started, and is waited for, rather than missed.
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );
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
  // Comparing with visual baselines is off while the redesign settles (Nikki, Oct 8): the screens are checked by
  // hand. VISUAL_COMPARE=1 turns it back on; axe, the overflow check and layout shift run either way.
  if (process.env.VISUAL_COMPARE === "1" && !process.env.SKIP_VISUAL) {
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true, stylePath: FULL_PAGE_STYLE });
  }
}

/** Keeps a page's recording in the review gallery, as `<name>.webm`. */
export async function keep(page: Page, name: string): Promise<void> {
  await page.close();
  const gallery = process.env.GALLERY_DIR!;
  mkdirSync(gallery, { recursive: true });
  copyFileSync(await page.video()!.path(), `${gallery}/${name}.webm`);
}

/** A pause in a recording, so a viewer can see what happened. */
export const slowly = (page: Page, ms = 900) => page.waitForTimeout(ms);

/** An event stream that sends one snapshot of the session, then closes. */
export const snapshot = (session: unknown) => ({
  events: `event: session\ndata: ${JSON.stringify(session)}\n\n`,
});
