import { expect, test, type Page } from "@playwright/test";
import { ADMIN_URL, APP_URL, HOST_URL } from "./sites";
import { signIn } from "./support";

/**
 * ./start.sh's three development servers side by side (scripts/e2e/start-check.sh): each site opens one screen, and every
 * screen's code, which each fetches once the first has painted, arrives. Each keeps its own dependency cache and
 * optimises its lazily loaded packages up front (vite.config.ts), so none answers "504 Outdated Optimize Dep" for a
 * module the others' optimising made stale, and the console never shows "Something went wrong".
 */
const SCREENS = [
  {
    site: "the renter app",
    origin: APP_URL,
    path: "/",
    ready: { role: "heading", name: "What do you want to run?" },
  },
  { site: "the host site", origin: HOST_URL, path: "/laptops/", ready: { role: "navigation", name: "Main" } },
  // The console loads its frame and, after its first paint, every screen's code: the QR code's and the lists' too.
  { site: "the admin site", origin: ADMIN_URL, path: "/admin/", ready: null },
] as const;

const who = process.env.START_CHECK_USER ?? "nikki";

function watch(page: Page): { stale: string[]; errors: string[] } {
  const found = { stale: [] as string[], errors: [] as string[] };
  page.on("response", (response) => {
    if (response.status() === 504) {
      found.stale.push(new URL(response.url()).pathname);
    }
  });
  page.on("pageerror", (error) => found.errors.push(error.message));
  return found;
}

for (const screen of SCREENS) {
  test(`${screen.site}'s development server serves a screen and every screen's code`, async ({ page }) => {
    const found = watch(page);
    await signIn(page, who, screen.path, screen.origin);
    if (screen.ready) {
      await expect(page.getByRole(screen.ready.role, { name: screen.ready.name }).first()).toBeAttached({
        timeout: 60_000,
      });
    }
    // Every screen's code is fetched once the first has painted (src/sites/screen.tsx); a stale one would be a 504.
    await page.waitForLoadState("networkidle", { timeout: 120_000 });
    await expect(page.getByText("Something went wrong")).toHaveCount(0);
    expect(found.stale, "modules answered 504 Outdated Optimize Dep").toEqual([]);
    expect(found.errors).toEqual([]);
  });
}
