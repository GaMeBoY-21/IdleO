import { expect, test } from "@playwright/test";
import { copyFileSync, mkdirSync } from "node:fs";
import * as data from "./fixtures";
import { fakeApi } from "./support";

/**
 * A short screen recording for the review gallery: sign-in to the home screen on a phone, as a renter sees it
 * (fixtures for the API, the local sign-in server for signing in). Only when GALLERY_DIR is set.
 */
test.skip(!process.env.GALLERY_DIR, "only for the review gallery (GALLERY_DIR)");

test("sign-in to home on a phone", async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
  await fakeApi(page, {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    "/v1/sessions/[^/]+": { json: data.running },
  });
  await page.goto("/sign-in/");
  await page.waitForTimeout(800);
  await page.getByLabel("Email", { exact: true }).pressSequentially("asha@example.com", { delay: 60 });
  await page.waitForTimeout(400);
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByRole("region", { name: "Running now" })).toBeVisible();
  await page.waitForTimeout(1500);
  await page.mouse.wheel(0, 500);
  await page.waitForTimeout(1200);
  await page.getByRole("region", { name: "Running now" }).getByRole("link").click();
  await expect(page.getByRole("heading", { name: /QLoRA/ })).toBeVisible();
  await page.waitForTimeout(1500);
  await page.close();
  const gallery = process.env.GALLERY_DIR!;
  mkdirSync(gallery, { recursive: true });
  copyFileSync(await page.video()!.path(), `${gallery}/sign-in-to-home--phone.webm`);
});
