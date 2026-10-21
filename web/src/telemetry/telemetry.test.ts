import { afterEach, describe, expect, it, vi } from "vitest";
import { holdEarlyErrors, takeEarlyErrors, type EarlyError } from "./early-errors";
import { privacyAsked } from "./privacy";
import { deviceOf, routeOf } from "./report";

describe("what a report says about where it came from", () => {
  it("names a route, never a thing: ids out, query and hash never in", () => {
    expect(routeOf("/session/")).toBe("/session/");
    expect(routeOf("/link/0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b/")).toBe("/link/:id/");
    expect(routeOf("/hosting/42/")).toBe("/hosting/:id/");
    expect(routeOf("/Account/Keys/")).toBe("/account/keys/");
    expect(routeOf("/a weird path <x>/")).toBe("/other");
  });

  it("is a phone or a laptop, at the app's own breakpoint", () => {
    expect(deviceOf(390)).toBe("phone");
    expect(deviceOf(767)).toBe("phone");
    expect(deviceOf(768)).toBe("laptop");
    expect(deviceOf(1440)).toBe("laptop");
  });
});

describe("a visitor who asks not to be tracked", () => {
  it("is heard from Do Not Track and from Global Privacy Control", () => {
    expect(privacyAsked({ doNotTrack: "1" })).toBe(true);
    expect(privacyAsked({ globalPrivacyControl: true })).toBe(true);
    expect(privacyAsked({ doNotTrack: "0", globalPrivacyControl: false })).toBe(false);
    expect(privacyAsked({})).toBe(false);
  });
});

describe("errors before the reporter has loaded", () => {
  afterEach(() => vi.restoreAllMocks());

  it("are held, then handed over, and later ones go straight through", () => {
    holdEarlyErrors();
    window.dispatchEvent(
      new ErrorEvent("error", {
        message: "boom",
        filename: "https://app.example/assets/a.js",
        lineno: 3,
        colno: 9,
      }),
    );
    const seen: EarlyError[] = [];
    takeEarlyErrors((error) => seen.push(error));
    expect(seen).toEqual([
      { message: "boom", source: "https://app.example/assets/a.js", line: 3, column: 9, pathname: "/" },
    ]);
    window.dispatchEvent(new ErrorEvent("error", { message: "later" }));
    expect(seen.map((error) => error.message)).toEqual(["boom", "later"]);
  });
});
