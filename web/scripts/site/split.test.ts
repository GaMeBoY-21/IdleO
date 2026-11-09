// @vitest-environment node
import { describe, expect, it } from "vitest";
import { SCREENS as APP_SCREENS } from "../../src/sites/app/screens.ts";
import { SCREENS as HOST_SCREENS } from "../../src/sites/host/screens.ts";
import { OLD_LANDING } from "../../src/site/paths.ts";
import { pages } from "../../src/site/render.tsx";
import { siteContext } from "./context.ts";

/**
 * The website and the app's sites are builds with their own addresses (ADR 0029, ADR 0058): none serves another's pages.
 * The only path they share is "/", the website's landing page and each site's signed-in Home, each on its own address.
 */
describe("the website and the app's sites", () => {
  const website = pages(
    siteContext("https://api.example.invalid", "https://app.example.invalid", "https://host.example.invalid"),
  ).map((page) => page.path);
  const app = [...new Set([...Object.keys(APP_SCREENS), ...Object.keys(HOST_SCREENS)])];

  it("share no page but the root", () => {
    expect(app.filter((path) => website.includes(path))).toEqual(["/"]);
  });

  it("leave the old landing page to the website's redirect, not the app", () => {
    expect(app).not.toContain(OLD_LANDING);
    expect(website).not.toContain(OLD_LANDING);
  });

  it("keep the app's screens out of the website", () => {
    for (const path of ["/sign-in/", "/book/", "/hosting/", "/credit/", "/account/", "/admin/"]) {
      expect(website, path).not.toContain(path);
    }
  });
});
