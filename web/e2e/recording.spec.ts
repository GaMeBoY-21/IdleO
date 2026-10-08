import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { fakeApi, keep, slowly, snapshot } from "./support";

/**
 * Short screen recordings for the review gallery, with invented data (fixtures for the API and storage, the local
 * sign-in server for signing in). Only when GALLERY_DIR is set.
 */
test.skip(!process.env.GALLERY_DIR, "only for the review gallery (GALLERY_DIR)");

test("sign-in to home on a phone", async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
  await fakeApi(page, {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions/[^/]+/events": snapshot(data.running),
    "/v1/sessions/[^/]+/logs": {
      json: { chunks: data.logChunks(data.running), nextCursor: "c", complete: false },
    },
    "/v1/sessions/[^/]+": { json: data.running },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  });
  await page.goto("/sign-in/");
  await slowly(page, 800);
  await page.getByLabel("Email", { exact: true }).pressSequentially("asha@example.com", { delay: 60 });
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByRole("region", { name: "Running now" })).toBeVisible();
  await slowly(page, 1500);
  await page.getByRole("region", { name: "Running now" }).getByRole("link").click();
  await expect(page.getByRole("heading", { name: /QLoRA/ })).toBeVisible();
  await slowly(page, 1500);
  await keep(page, "sign-in-to-home--phone");
});

test("stopping a session on a phone", async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
  const finishing: data.Session = {
    ...data.running,
    state: "FINISHING",
    events: [
      ...data.running.events,
      { state: "FINISHING", reason: "renter_stopped", at: data.NOW.toISOString() },
    ],
  };
  const stopped: data.Session = {
    ...finishing,
    state: "COMPLETED",
    settled: true,
    accruedPaise: 0,
    reservedPaise: 0,
    chargedPaise: data.running.chargedPaise + data.running.accruedPaise,
    endedAt: data.NOW.toISOString(),
    events: [...finishing.events, { state: "COMPLETED", reason: "stopped", at: data.NOW.toISOString() }],
  };
  let stage: data.Session = data.running;
  await fakeApi(page, {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions/[^/]+/stop": () => {
      stage = finishing;
      setTimeout(() => (stage = stopped), 2500);
      return { json: finishing };
    },
    "/v1/sessions/[^/]+/events": () => snapshot(stage),
    "/v1/sessions/[^/]+/logs": {
      json: { chunks: data.logChunks(data.running), nextCursor: "c", complete: false },
    },
    "/v1/sessions/[^/]+/outputs": { json: data.outputs },
    "/v1/sessions/[^/]+": () => ({ json: stage }),
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  });
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await page.goto(`/session/?id=${data.running.id}`);
  await expect(page.getByTestId("state-chip").first()).toHaveText("Running");
  await slowly(page, 1500);
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await slowly(page, 1200);
  await page.getByRole("dialog").getByRole("button", { name: "Stop the session" }).click();
  await expect(page.getByTestId("state-chip").first()).toHaveText("Completed", { timeout: 20_000 });
  await slowly(page, 2000);
  await keep(page, "stopping-a-session--phone");
});
