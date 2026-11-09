import { describe, expect, it } from "vitest";
import { appLink, hostLink, placeOf, siteLink } from "./site-links";

describe("site links", () => {
  it("is the website's address, which the local stack puts at :3002, never a path on the app", () => {
    expect(siteLink("/")).toBe("http://localhost:3002/");
    expect(siteLink("/terms/")).toBe("http://localhost:3002/terms/");
  });

  it("leads to the renter app and the host site at their own addresses, :3000 and :3003 locally", () => {
    expect(appLink("/book/")).toBe("http://localhost:3000/book/");
    expect(hostLink("/payout-details/")).toBe("http://localhost:3003/payout-details/");
  });
});

describe("where a notice's link leads, from the renter app (ADR 0058)", () => {
  it("keeps the renter's own paths here", () => {
    expect(placeOf("/session/?id=1#outputs")).toEqual({ href: "/session/?id=1#outputs", here: true });
    expect(placeOf("/account/")).toEqual({ href: "/account/", here: true });
  });

  it("sends a host's links, new and old, to the host site", () => {
    expect(placeOf("/earnings/", "host")).toEqual({ href: "http://localhost:3003/earnings/", here: false });
    expect(placeOf("/hosting/machine/?id=7")).toEqual({
      href: "http://localhost:3003/laptop/?id=7",
      here: false,
    });
    expect(placeOf("/hosting/earnings/")).toEqual({ href: "http://localhost:3003/earnings/", here: false });
  });

  it("keeps a path the notice says is the renter app's here", () => {
    expect(placeOf("/credit/", "app")).toEqual({ href: "/credit/", here: true });
  });
});
