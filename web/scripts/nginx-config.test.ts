// @vitest-environment node
import { describe, expect, it } from "vitest";
import { serverBlock, type ServeRules } from "./nginx-config";

const rules: ServeRules = {
  headers: { "Content-Security-Policy": "default-src 'self'", "X-Content-Type-Options": "nosniff" },
  pathHeaders: {
    "/credit/": { "Content-Security-Policy": "default-src 'self' https://checkout.razorpay.com" },
  },
  redirects: { "/about/": "/" },
  rewrites: { "/link/": "/link/index.html" },
};

/** The lines of the location block that starts with `opening`. */
function location(config: string, opening: string): string[] {
  const lines = config.split("\n");
  const start = lines.indexOf(`    ${opening} {`);
  expect(start, opening).toBeGreaterThan(-1);
  return lines.slice(start + 1, lines.indexOf("    }", start)).map((line) => line.trim());
}

describe("serverBlock", () => {
  const config = serverBlock(rules, "/usr/share/nginx/dist", 8080);

  it("sends the build's headers from every location that serves a page", () => {
    for (const opening of [
      "location /",
      "location = /404.html",
      "location ^~ /assets/",
      "location ^~ /link/",
    ]) {
      expect(location(config, opening)).toContain(
        `add_header "Content-Security-Policy" "default-src 'self'" always;`,
      );
    }
  });

  it("gives a page with headers of its own only those, at both its addresses", () => {
    for (const opening of ["location = /credit/", "location = /credit/index.html"]) {
      const block = location(config, opening);
      expect(block).toContain(
        `add_header "Content-Security-Policy" "default-src 'self' https://checkout.razorpay.com" always;`,
      );
      expect(block).not.toContain(`add_header "Content-Security-Policy" "default-src 'self'" always;`);
      expect(block).toContain("try_files /credit/index.html =404;");
    }
  });

  it("redirects permanently, rewrites a screen's addresses to its page, and hides the rule files", () => {
    expect(location(config, "location = /about/")).toContain("return 301 /;");
    expect(location(config, "location ^~ /link/")).toContain("try_files /link/index.html =404;");
    expect(location(config, "location = /headers.json")).toEqual(["return 404;"]);
  });

  it("caches hashed assets for a year and pages not at all", () => {
    expect(location(config, "location ^~ /assets/")).toContain(
      `add_header Cache-Control "public, max-age=31536000, immutable" always;`,
    );
    expect(location(config, "location /")).toContain(`add_header Cache-Control "no-cache" always;`);
  });

  it("refuses a value nginx would read as a variable, and a path that isn't plain", () => {
    expect(() => serverBlock({ ...rules, headers: { "X-Bad": "a $b" } }, "/srv", 8080)).toThrow(/nginx/);
    expect(() => serverBlock({ ...rules, redirects: { "/a b/": "/" } }, "/srv", 8080)).toThrow(/plain path/);
  });
});
