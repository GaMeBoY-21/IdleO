import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { fakeApi, fakeStorage, keep, slowly, snapshot, type Answer } from "./support";

/**
 * The laptop recording for the review gallery (e2e/recording.spec.ts has the phone ones): booking, from picking a
 * service to downloading the outputs, with invented data: the model from My files, the examples uploaded. Only when
 * GALLERY_DIR is set.
 */
test.skip(!process.env.GALLERY_DIR, "only for the review gallery (GALLERY_DIR)");
test.use({
  viewport: { width: 1280, height: 800 },
  isMobile: false,
  hasTouch: false,
  video: { mode: "on", size: { width: 1280, height: 800 } },
});

const MIB = 1024 * 1024;

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
    "/v1/uploads": (_call, request) =>
      request.method() === "POST"
        ? { json: data.newUpload("support-chats.jsonl", 12 * MIB), status: 201 }
        : { json: data.uploadsPage() },
    "/v1/uploads/[^/]+/part-urls": (_call, request) => ({
      json: data.partLinks((request.postDataJSON() as { partNumbers: number[] }).partNumbers),
    }),
    "/v1/uploads/[^/]+/complete": {
      json: data.newUpload("support-chats.jsonl", 12 * MIB, "ready"),
    },
    "/v1/session-quotes": (_call, request) =>
      data.quoteAnswer(request.postDataJSON() as { maxSpendPaise: number }),
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
  await page.getByRole("button", { name: /^Fine-tune a model/ }).click();
  await slowly(page);
  const model = page.getByRole("region", { name: "1. Your model" });
  await model.getByText("From My files").click();
  await slowly(page, 600);
  await model.getByRole("button", { name: /^my-llm-7b\// }).click();
  await slowly(page, 600);
  await page.getByTestId("data-file").setInputFiles({
    name: "support-chats.jsonl",
    mimeType: "application/jsonl",
    buffer: Buffer.alloc(12 * MIB, 7),
  });
  await expect(page.getByText("12 MB · in My files")).toBeVisible({ timeout: 20_000 });
  await slowly(page);
  await page.getByRole("button", { name: /^Quick/ }).click();
  await expect(page.getByText(/is held while it runs/)).toBeVisible();
  await slowly(page, 2000);
  await page.getByRole("button", { name: "Request a GPU" }).click();
  await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 20_000 });
  await slowly(page, 3000);
  await expect(page.getByTestId("state-chip").first()).toHaveText("Completed", { timeout: 20_000 });
  await page.getByRole("link", { name: "Download outputs" }).click();
  await slowly(page, 2500);
  await keep(page, "booking-from-template-to-outputs--laptop");
});
