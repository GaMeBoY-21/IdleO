import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import { routes } from "./fixtures-admin-routes";
import { fakeApi, signIn, type Answer } from "./support";

/**
 * A long list scrolls smoothly (ADR 0057). In the serial project, beside the Lighthouse runs, as a frame's length means
 * nothing while other browsers compete for the machine: on CI's parallel shards a runner busy with other tests made it
 * twice as long. The budget is 50 ms, at reading speed with every row real, and flicked from end to end.
 */
async function open(page: Page, path: string, overrides: Record<string, Answer> = {}) {
  await fakeApi(page, routes(overrides));
  await signIn(page, "asha", path);
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
});

test("10,000 rows are windowed and scroll with no frame over 50 ms", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const row = admin.sessions.sessions[0]!;
  const many = Array.from({ length: 10_000 }, (_, index) => ({
    ...row,
    id: `00000000-0000-7000-8000-${String(index).padStart(12, "0")}`,
  }));
  await open(page, "/admin/sessions/", {
    "/admin/v1/sessions": { json: { sessions: many, nextCursor: null } },
  });
  const region = page.getByRole("region", { name: "Sessions" });
  await expect(region.locator("tr[data-row-key]").first()).toBeVisible();
  const inPage = await region.locator("tr[data-row-key]").count();
  expect(inPage, "rows in the page").toBeLessThan(100);
  await expect(page.getByRole("table", { name: "Sessions" })).toHaveAttribute("aria-rowcount", "10001");
  /** The longest frame while scrolling `pixels` a frame for 4 seconds. */
  const worstFrame = (pixels: number) =>
    region.evaluate(async (element, step) => {
      element.scrollTop = 0;
      await new Promise((resolve) => setTimeout(resolve, 500));
      const lengths: number[] = [];
      let at = performance.now();
      const started = at;
      return await new Promise<{ frames: number; worst: number }>((resolve) => {
        const tick = (now: number) => {
          lengths.push(now - at);
          at = now;
          element.scrollTop += step;
          if (now - started < 4_000) {
            requestAnimationFrame(tick);
          } else {
            // The first is only how long it waited to begin.
            resolve({ frames: lengths.length, worst: Math.max(...lengths.slice(1)) });
          }
        };
        requestAnimationFrame(tick);
      });
    }, pixels);
  // As someone reads down it, with every row real; and flicked from end to end.
  const reading = await worstFrame(60);
  await expect(region.locator("tr[data-row-key]").first()).toBeVisible();
  const flicked = await worstFrame(1_900);
  for (const [how, measured] of [
    ["reading", reading],
    ["flicked", flicked],
  ] as const) {
    console.log(
      `console table, ${how}: ${measured.frames} frames over 10,000 rows, the worst ${measured.worst.toFixed(1)} ms`,
    );
    expect(measured.worst, how).toBeLessThanOrEqual(50);
  }
  await expect(region.locator("tr[data-row-key]").last()).toBeVisible();
});
