import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { fakeApi, fakeStorage, keep, slowly, snapshot, type Answer } from "./support";

/**
 * The laptop recording for the review gallery (e2e/recording.spec.ts has the phone ones): booking, from picking a
 * template to downloading the outputs, with invented data. Only when GALLERY_DIR is set.
 */
test.skip(!process.env.GALLERY_DIR, "only for the review gallery (GALLERY_DIR)");
test.use({
  viewport: { width: 1280, height: 800 },
  isMobile: false,
  hasTouch: false,
  video: { mode: "on", size: { width: 1280, height: 800 } },
});

const MIB = 1024 * 1024;
const echo = data.templateList.find((t) => t.id === "echo-input")!;

test("booking, from template to outputs", async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
  const booked = { ...data.finding, id: data.completed.id };
  const running = {
    ...data.completed,
    state: "RUNNING",
    settled: false,
    endedAt: null,
    chargedPaise: 0,
    accruedPaise: 12,
    reservedPaise: 540,
    events: data.completed.events.slice(0, 4),
  };
  let stage: data.Session = booked;
  const api: Record<string, Answer> = {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/availability": { json: data.availability },
    "/v1/uploads": { json: data.newUpload("greetings.bin", 12 * MIB), status: 201 },
    "/v1/uploads/[^/]+/complete": {
      json: { ...data.newUpload("greetings.bin", 12 * MIB), status: "ready" },
    },
    "/v1/session-quotes": (_call, request) => {
      const body = request.postDataJSON() as { maxPricePaisePerMin: number; maxSpendPaise: number };
      return { json: data.quote(body.maxPricePaisePerMin, body.maxSpendPaise) };
    },
    "/v1/sessions/[^/]+/events": () => snapshot(stage),
    "/v1/sessions/[^/]+/logs": () => ({
      json: {
        chunks: stage === booked ? [] : data.logChunks(data.running, 3),
        nextCursor: "c",
        complete: false,
      },
    }),
    "/v1/sessions/[^/]+/outputs": { json: data.outputs },
    "/v1/sessions/[^/]+": () => ({ json: stage }),
    "/v1/sessions": (_call, request) => {
      if (request.method() === "POST") {
        setTimeout(() => (stage = running), 2500);
        setTimeout(() => (stage = data.completed), 9000);
        return { json: booked, status: 201 };
      }
      return { json: data.sessionsPage(data.asha.sessions) };
    },
  };
  await fakeApi(page, api);
  await fakeStorage(page, { delayMs: 700 });
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await page.goto("/book/");
  await slowly(page, 1200);
  await page.getByRole("button", { name: new RegExp(echo.name) }).click();
  await slowly(page);
  await page.getByLabel("Greeting", { exact: true }).pressSequentially("hello", { delay: 80 });
  await page.getByLabel("Repeat (optional)").fill("3");
  await slowly(page, 500);
  await page.getByTestId("input-file").setInputFiles({
    name: "greetings.bin",
    mimeType: "application/octet-stream",
    buffer: Buffer.alloc(12 * MIB, 7),
  });
  await expect(page.getByText("12 MB · uploaded")).toBeVisible({ timeout: 20_000 });
  await slowly(page);
  await page.getByRole("button", { name: "Review" }).click();
  await expect(page.getByText(/is held now/)).toBeVisible();
  await slowly(page, 2000);
  await page.getByRole("button", { name: /Book for up to/ }).click();
  await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 20_000 });
  await slowly(page, 3000);
  await expect(page.getByTestId("state-chip").first()).toHaveText("Completed", { timeout: 20_000 });
  await page.getByRole("link", { name: "Download outputs" }).click();
  await slowly(page, 2500);
  await keep(page, "booking-from-template-to-outputs--laptop");
});
