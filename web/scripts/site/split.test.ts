// @vitest-environment node
import { describe, expect, it } from "vitest";
import { SCREENS } from "../../src/screens.ts";
import { OLD_LANDING } from "../../src/site/paths.ts";
import { pages } from "../../src/site/render.tsx";
import { siteContext } from "./context.ts";

/**
 * The website and the app are two builds with their own addresses (ADR 0029): neither serves the other's pages. The only
 * path they share is "/", the website's landing page and the app's signed-in Home, each on its own address.
 */
describe("the website and the app", () => {
  const website = pages(siteContext("https://api.example.invalid", "https://app.example.invalid")).map(
    (page) => page.path,
  );
  const app = Object.keys(SCREENS);

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
