import { expect, test } from "@playwright/test";
import { routes } from "./fixtures-admin-routes";
import { fakeApi, signIn } from "./support";

/**
 * The console is the admin site's alone (ADR 0058), and the renter app's build holds none of it (scripts/site-modules.ts),
 * so the app's 150 KB budget is untouched. Every app screen, as a person first lands on it: nothing it downloads holds the
 * admin API's paths or the console's own words.
 */
const APP_SCREENS = [
  "/",
  "/book/",
  "/credit/",
  "/sessions/",
  "/files/",
  "/account/",
  "/account/help/",
  "/account/data/",
];

for (const screen of APP_SCREENS) {
  test(`${screen} downloads none of the console`, async ({ page }) => {
    const scripts: string[] = [];
    page.on("response", (response) => {
      if (response.url().endsWith(".js") && response.status() === 200) {
        scripts.push(response.url());
      }
    });
    await fakeApi(page, routes());
    await signIn(page, "asha", screen);
    await page.waitForLoadState("networkidle");
    expect(scripts.length, "scripts loaded").toBeGreaterThan(0);
    for (const url of scripts) {
      const body = await (await page.request.get(url)).text();
      expect(body.includes("/admin/v1/"), `${url} holds the admin API`).toBe(false);
      expect(body.includes("Leave the console"), `${url} holds the console`).toBe(false);
      expect(url, "the console's own chunk").not.toMatch(/AdminConsole/);
    }
  });
}
