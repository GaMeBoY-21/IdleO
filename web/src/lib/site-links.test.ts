import { describe, expect, it } from "vitest";
import { siteLink } from "./site-links";

describe("siteLink", () => {
  it("is the website's address, which the local stack puts at :3002, never a path on the app", () => {
    expect(siteLink("/")).toBe("http://localhost:3002/");
    expect(siteLink("/terms/")).toBe("http://localhost:3002/terms/");
  });
});
