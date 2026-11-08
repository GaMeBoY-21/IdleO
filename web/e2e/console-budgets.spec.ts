import { readFileSync, writeFileSync } from "node:fs";
import { createHmac } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";
import { signIn } from "./support";

/**
 * The console's budgets (ADR 0057), on the seeded data, in a real browser, by scripts/e2e/console-budgets.sh. Each screen:
 * from following its link to its content painted, no skeleton left, within 1 s; and from a sort or a filter changing to
 * the new rows painted, within 200 ms. Measured in the page, by frame, so Playwright's own time doesn't count.
 */
const PAINT_MS = 1_000;
const CHANGE_MS = 200;

const SCREENS = [
  ...readFileSync(new URL("../src/features/admin/AdminConsole.tsx", import.meta.url), "utf8").matchAll(
    /\{ href: "(\/admin\/[^"]*)", label: "([^"]+)"/g,
  ),
].map((match) => ({ href: match[1]!, label: match[2]! }));

function code(secret: string): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) {
    bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[19]! & 0x0f;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

async function enter(page: Page): Promise<void> {
  await signIn(page, process.env.E2E_ADMIN_USER!, "/admin/");
  await expect(page.getByRole("heading", { level: 1, name: "Set up your authenticator" })).toBeVisible();
  const secret = (await page.getByText(/^[A-Z2-7]{32}$/).textContent())!;
  // A code typed near a step's end may land in the next.
  const into = (Date.now() / 1000) % 30;
  if (into > 24) {
    await page.waitForTimeout((30 - into + 0.5) * 1000);
  }
  await page.getByLabel("Code from your authenticator app").fill(code(secret));
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "I've saved them" }).click();
  await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible({ timeout: 30_000 });
}

declare global {
  interface Window {
    /** Whether the page has settled: its heading is the one asked for, and nothing says it's still loading. */
    consoleSettled: (heading: string | null) => boolean;
  }
}

test("every screen paints within its budget, and a sort or filter changes within its own", async ({
  page,
}) => {
  test.setTimeout(600_000);
  await enter(page);
  await page.evaluate(() => {
    window.consoleSettled = (heading) => {
      const h1 = document.querySelector("main h1");
      return (
        h1 !== null &&
        (heading === null || h1.textContent === heading) &&
        document.querySelector('main [role="status"][aria-label^="Loading"]') === null
      );
    };
  });
  const report: {
    screen: string;
    paintMs: number;
    rows: number;
    changeMs: number | null;
    change: string | null;
  }[] = [];
  for (const screen of SCREENS) {
    const paintMs = await page.evaluate(
      async ({ href, label }) => {
        const started = performance.now();
        document.querySelector<HTMLAnchorElement>(`nav[aria-label="Admin"] a[href="${href}"]`)!.click();
        // A frame first, so the old screen isn't what's measured.
        await new Promise(requestAnimationFrame);
        for (;;) {
          if (window.consoleSettled(label === "Your access" ? null : label)) {
            await new Promise(requestAnimationFrame);
            return performance.now() - started;
          }
          if (performance.now() - started > 30_000) {
            return Number.POSITIVE_INFINITY;
          }
          await new Promise(requestAnimationFrame);
        }
      },
      { href: screen.href, label: screen.label },
    );
    // The control room's stream stays open, so the network never goes idle: a moment for what follows the paint.
    await page.waitForTimeout(400);
    const rows = await page.locator("main tbody tr").count();
    const change = await page.evaluate(async () => {
      const sorter = document.querySelector<HTMLButtonElement>("main th button");
      const select = document.querySelector<HTMLSelectElement>("main select");
      const started = performance.now();
      let what: string;
      if (sorter) {
        what = `sort by ${sorter.textContent}`;
        sorter.click();
      } else if (select && select.options.length > 1) {
        what = `filter by ${select.labels[0]?.textContent ?? select.name}`;
        select.selectedIndex = (select.selectedIndex + 1) % select.options.length;
        select.dispatchEvent(new Event("change", { bubbles: true }));
      } else {
        return null;
      }
      await new Promise(requestAnimationFrame);
      for (;;) {
        if (window.consoleSettled(null)) {
          await new Promise(requestAnimationFrame);
          return { what, ms: performance.now() - started };
        }
        if (performance.now() - started > 30_000) {
          return { what, ms: Number.POSITIVE_INFINITY };
        }
        await new Promise(requestAnimationFrame);
      }
    });
    report.push({
      screen: screen.label,
      paintMs: Math.round(paintMs),
      rows,
      changeMs: change === null ? null : Math.round(change.ms),
      change: change?.what ?? null,
    });
    // The control room's stream stays open, so the network never goes idle: a moment for what follows the paint.
    await page.waitForTimeout(400);
  }
  for (const row of [...report].sort((one, other) => other.paintMs - one.paintMs)) {
    console.log(
      `budget: ${row.screen}: paint ${row.paintMs} ms; ${row.change ?? "nothing to sort or filter"}${row.changeMs === null ? "" : ` ${row.changeMs} ms`}`,
    );
  }
  if (process.env.CONSOLE_BUDGETS_REPORT) {
    writeFileSync(process.env.CONSOLE_BUDGETS_REPORT, JSON.stringify(report, null, 2));
  }
  expect(
    report.filter((row) => row.paintMs > PAINT_MS).map((row) => `${row.screen} ${row.paintMs} ms`),
  ).toEqual([]);
  expect(
    report
      .filter((row) => row.changeMs !== null && row.changeMs > CHANGE_MS)
      .map((row) => `${row.screen}: ${row.change} ${row.changeMs} ms`),
  ).toEqual([]);
});
