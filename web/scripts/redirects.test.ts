// @vitest-environment node
import { describe, expect, it } from "vitest";
import { MOVED_FROM_APP } from "../src/sites/host/paths.ts";
import { pagesHeaders, pagesRedirects } from "./pages-config.ts";
import { parseRedirects, redirectFor } from "../src/sites/redirects.ts";

const HOST = "https://host.example.invalid";
const moved = parseRedirects(
  Object.fromEntries(Object.entries(MOVED_FROM_APP).map(([from, to]) => [from, `${HOST}${to}`])),
);

describe("the renter app's old hosting addresses (ADR 0058)", () => {
  it("go to the host site's, query and all", () => {
    expect(redirectFor(moved, "/hosting/", "")).toBe(`${HOST}/`);
    expect(redirectFor(moved, "/hosting", "")).toBe(`${HOST}/`);
    expect(redirectFor(moved, "/hosting/machine/", "?id=42")).toBe(`${HOST}/laptop/?id=42`);
    expect(redirectFor(moved, "/hosting/earnings/", "")).toBe(`${HOST}/earnings/`);
    expect(redirectFor(moved, "/hosting/payout-details/", "")).toBe(`${HOST}/payout-details/`);
  });

  it("keep a link's id", () => {
    expect(redirectFor(moved, "/link/0199f0a2/", "")).toBe(`${HOST}/link/0199f0a2/`);
  });

  it("leave the renter's own screens alone", () => {
    for (const path of ["/", "/book/", "/sessions/", "/credit/", "/hostingx/"]) {
      expect(redirectFor(moved, path, ""), path).toBeNull();
    }
  });

  it("refuse a prefix to a path, and a target that isn't plain", () => {
    expect(() => parseRedirects({ "/a/*": "/b/" })).toThrow(/plain target/);
    expect(() => parseRedirects({ "/a/": "javascript:alert(1)" })).toThrow(/plain target/);
  });
});

describe("Cloudflare Pages' rule files", () => {
  const rules = {
    headers: { "Content-Security-Policy": "default-src 'self'" },
    pathHeaders: {
      "/credit/": { "Content-Security-Policy": "default-src 'self' https://checkout.razorpay.com" },
    },
    redirects: { "/hosting/machine/": `${HOST}/laptop/`, "/link/*": `${HOST}/link/*` },
    rewrites: { "/link/": "/link/index.html" },
  };

  it("redirect permanently, keep a prefix's rest, and rewrite a screen's addresses to its page", () => {
    expect(pagesRedirects(rules).split("\n")).toEqual([
      `/hosting/machine/ ${HOST}/laptop/ 301`,
      `/hosting/machine ${HOST}/laptop/ 301`,
      `/link/* ${HOST}/link/:splat 301`,
      "/link/* /link/index.html 200",
      "",
    ]);
  });

  it("send the build's headers everywhere, and a page's own instead of them", () => {
    const headers = pagesHeaders(rules);
    expect(headers).toContain(
      "/*\n  Content-Security-Policy: default-src 'self'\n  Cache-Control: no-cache\n",
    );
    expect(headers).toContain(
      "/credit/\n  ! Content-Security-Policy\n  Content-Security-Policy: default-src 'self' https://checkout.razorpay.com\n",
    );
    expect(headers).toContain("/assets/*\n  Cache-Control: public, max-age=31536000, immutable\n");
  });
});
