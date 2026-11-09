// The frame each site draws (ADR 0058): run as the renter app's and again as the host site's (vitest.config.ts).
import { describe, expect, it } from "vitest";
import { config } from "@/config";
import { placeOf } from "@/lib/site-links";
import { MOVED_FROM_APP } from "@/sites/host/paths";
import { nav } from "@site/site";
import { SCREENS } from "@site/screens";

const RENTING = ["/book/", "/credit/", "/session/", "/files/", "/account/keys/", "/account/images/"];

describe(`the ${config.site} site's frame`, () => {
  it("leads only to its own screens", () => {
    for (const { href } of [...nav.destinations, ...nav.phoneDestinations, ...nav.accountSections]) {
      expect(Object.keys(SCREENS), href).toContain(href);
    }
  });

  it("has five tabs on a phone, and More", () => {
    expect(nav.phoneDestinations).toHaveLength(5);
  });

  it("has no screen of the other site's", () => {
    const own = Object.keys(SCREENS);
    if (config.site === "app") {
      expect(own.filter((path) => path.startsWith("/hosting/") || path === "/link/")).toEqual([]);
    } else {
      expect(own.filter((path) => RENTING.includes(path))).toEqual([]);
    }
  });

  it("links to the other site once, quietly, at its own address", () => {
    expect(nav.otherSite).toEqual(
      config.site === "app"
        ? { href: "http://localhost:3003/", label: "Earn with your laptop" }
        : { href: "http://localhost:3000/", label: "Rent a GPU" },
    );
  });

  it("answers every old hosting address on the host site", () => {
    if (config.site === "host") {
      for (const to of Object.values(MOVED_FROM_APP)) {
        expect(Object.keys(SCREENS), to).toContain(to.replace(/\*$/, ""));
      }
    }
  });
});

describe(`a notice's link on the ${config.site} site`, () => {
  it("opens a host's on the host site and a renter's on the renter app", () => {
    const host = placeOf("/laptop/?id=7", "host");
    const renter = placeOf("/session/?id=1", "app");
    if (config.site === "host") {
      expect(host).toEqual({ href: "/laptop/?id=7", here: true });
      expect(renter).toEqual({ href: "http://localhost:3000/session/?id=1", here: false });
      expect(placeOf("/account/help/")).toEqual({ href: "/account/help/", here: true });
      // A notice from before the site was named: a renter's path, so the renter app's.
      expect(placeOf("/session/?id=1")).toEqual({ href: "http://localhost:3000/session/?id=1", here: false });
    } else {
      expect(host.here).toBe(false);
      expect(renter.here).toBe(true);
    }
  });
});
