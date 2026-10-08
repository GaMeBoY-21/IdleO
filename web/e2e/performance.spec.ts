import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { fakeApi, signIn } from "./support";

/**
 * A 10 MiB log doesn't slow the session screen (web/README.md "The budgets"): about 150,000 lines arrive over half a
 * minute, a chunk of 1,000 lines at a time, as the agent's sink would upload them, while the renter scrolls the
 * panel with the keyboard and uses its buttons. On a CPU slowed four times, every interaction must paint within
 * 100 ms, and the page never holds more than the cap of 2,000 lines.
 */
const CHUNKS = 150;
const LINES_PER_CHUNK = 1_000;
const CAP = 2_000;

const line = (chunk: number, i: number) =>
  `epoch ${1 + Math.floor(chunk / 50)} step ${chunk * LINES_PER_CHUNK + i}/150000 loss ${(2.4 - chunk / 100).toFixed(4)} lr 0.0002 grad_norm 0.8731 tokens/s 1843`;
const text = (chunk: number) =>
  `${Array.from({ length: LINES_PER_CHUNK }, (_, i) => line(chunk, i)).join("\n")}\n`;

test("a 10 MiB log streams in without slowing the screen", async ({ page, browser }) => {
  test.skip(browser.browserType().name() !== "chromium", "CPU throttling is Chromium's");
  await page.setViewportSize({ width: 1280, height: 800 });
  const started = Date.now();
  // Chunks become available over 30 s; each read returns those not yet read, from the cursor it was given.
  const available = () => Math.min(CHUNKS, Math.floor((Date.now() - started) / 200));
  let bytes = 0;
  await fakeApi(page, {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions/[^/]+/events": () => ({
      events: `event: session\ndata: ${JSON.stringify(data.running)}\n\nevent: logs\ndata: {"attempt":1,"latestSeq":${available()}}\n\n`,
    }),
    "/v1/sessions/[^/]+/logs": (_call, request) => {
      const from = Number(new URL(request.url()).searchParams.get("cursor") ?? "0");
      const to = Math.min(available(), from + 100);
      const chunks = Array.from({ length: to - from }, (_, i) => ({
        attempt: 1,
        seq: from + i + 1,
        text: text(from + i),
      }));
      bytes += chunks.reduce((sum, chunk) => sum + chunk.text.length, 0);
      return { json: { chunks, nextCursor: String(to), complete: false } };
    },
    "/v1/sessions/[^/]+": { json: data.running },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  });
  await signIn(page, "asha", `/session/?id=${data.running.id}`);
  const panel = page.getByTestId("log-panel");
  await expect(panel).toBeVisible();

  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  await page.evaluate(() => {
    const worst = { ms: 0, name: "" };
    (window as unknown as { __worst: typeof worst }).__worst = worst;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (
          entry.duration > worst.ms &&
          ["keydown", "keyup", "pointerdown", "pointerup", "click"].includes(entry.name)
        ) {
          worst.ms = entry.duration;
          worst.name = entry.name;
        }
      }
    }).observe({ type: "event", durationThreshold: 16, buffered: true } as PerformanceObserverInit);
  });

  // While the log grows: scroll up and down with the keyboard, show earlier lines, jump back to the latest.
  let most = 0;
  while (
    available() < CHUNKS ||
    (await panel.locator("> div.h-4").last().textContent())?.includes("149999") !== true
  ) {
    await panel.focus();
    for (const key of ["PageUp", "PageUp", "ArrowUp", "PageDown", "End"]) {
      await page.keyboard.press(key);
    }
    const earlier = page.getByRole("button", { name: "Show earlier" });
    if (await earlier.isVisible()) {
      await earlier.click();
    }
    const latest = page.getByRole("button", { name: "Jump to latest" });
    if (await latest.isVisible()) {
      await latest.click();
    }
    most = Math.max(most, await panel.locator("> div.h-4").count());
    expect(Date.now() - started, "the whole log should arrive within two minutes").toBeLessThan(120_000);
  }
  most = Math.max(most, await panel.locator("> div.h-4").count());
  const worst = await page.evaluate(
    () => (window as unknown as { __worst: { ms: number; name: string } }).__worst,
  );
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
  console.log(
    `10 MiB log: ${(bytes / 1024 / 1024).toFixed(1)} MiB, ${CHUNKS * LINES_PER_CHUNK} lines in ${((Date.now() - started) / 1000).toFixed(1)} s; most lines in the page ${most}; slowest interaction ${Math.round(worst.ms)} ms (${worst.name || "none over 16 ms"})`,
  );
  expect(bytes).toBeGreaterThan(10 * 1024 * 1024);
  expect(most).toBeLessThanOrEqual(CAP);
  expect(worst.ms, `slowest interaction (${worst.name})`).toBeLessThanOrEqual(100);
});
