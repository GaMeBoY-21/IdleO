// @vitest-environment node
import { describe, expect, it } from "vitest";
import { sharedDomain, themeBootScript } from "./theme";

describe("the theme choice", () => {
  it("shares a cookie between the website and the app by the domain their addresses have in common", () => {
    expect(sharedDomain("app.idleo.in", "idleo.in")).toBe("idleo.in");
    expect(sharedDomain("app.idleo.in", "www.idleo.in")).toBe("idleo.in");
    expect(sharedDomain("app.example.co.in", "example.co.in")).toBe("example.co.in");
  });

  it("names no domain where there is none to share: localhost, addresses of different sites, an IP address", () => {
    expect(sharedDomain("localhost", "localhost")).toBe("");
    expect(sharedDomain("idleo.in", "example.com")).toBe("");
    expect(sharedDomain("10.0.0.5", "10.0.0.5")).toBe("");
  });

  it("is applied by a few hundred bytes at most, before the first paint, and only ever reads", () => {
    expect(Buffer.byteLength(themeBootScript)).toBeLessThan(300);
    expect(themeBootScript).toContain("document.cookie");
    expect(themeBootScript).not.toContain("document.cookie=");
    expect(themeBootScript).not.toContain("localStorage");
  });
});
