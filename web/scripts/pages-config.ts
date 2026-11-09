// Cloudflare Pages' own rule files for one build folder (ADR 0058, docs/runbooks/deployment.md "Four sites on Cloudflare
// Pages"): `_headers` and `_redirects`, written from the same rules scripts/serve.ts and scripts/nginx-config.ts follow
// (headers.json, path-headers.json, redirects.json, rewrites.json), so each of the four Pages projects serves its build
// as the local server and the web image do.
//
// Pages' `_redirects` keeps a prefix's rest with `:splat`. Whether it passes the query on isn't written down: the
// runbook's first-deploy check asks for /hosting/machine/?id=… and looks for the query at the other end.
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { readRules, type ServeRules } from "./nginx-config.ts";
import { parseRedirects } from "../src/sites/redirects.ts";

const IMMUTABLE = "public, max-age=31536000, immutable";

/** `_headers`: the build's headers on every path, a page's own instead on its page, long caching on hashed assets. */
export function pagesHeaders(rules: ServeRules): string {
  const block = (path: string, headers: Record<string, string>) => [
    path,
    ...Object.entries(headers).map(([name, value]) => `  ${name}: ${value}`),
  ];
  const lines = [...block("/*", { ...rules.headers, "Cache-Control": "no-cache" })];
  for (const assets of ["/assets/*", "/site-assets/*"]) {
    lines.push(...block(assets, { "Cache-Control": IMMUTABLE }));
  }
  for (const [page, headers] of Object.entries(rules.pathHeaders)) {
    for (const at of new Set([page, page.endsWith("/") ? `${page}index.html` : page])) {
      // `! Name` takes away what /* gave the page, so it has only its own.
      lines.push(at, ...Object.keys(headers).map((name) => `  ! ${name}`));
      lines.push(...block(at, headers).slice(1));
    }
  }
  return `${lines.join("\n")}\n`;
}

/** `_redirects`: every redirect, permanent, then every rewrite as a 200 (Pages serves the page, the address stays). */
export function pagesRedirects(rules: ServeRules): string {
  const lines: string[] = [];
  for (const { from, prefix, to } of parseRedirects(rules.redirects)) {
    if (prefix) {
      lines.push(`${from}* ${to}:splat 301`);
    } else {
      lines.push(`${from} ${to} 301`);
      if (from.endsWith("/") && from !== "/") {
        lines.push(`${from.slice(0, -1)} ${to} 301`);
      }
    }
  }
  for (const [prefix, page] of Object.entries(rules.rewrites)) {
    lines.push(`${prefix}* ${page} 200`);
  }
  return lines.length > 0 ? `${lines.join("\n")}\n` : "";
}

/** Writes both files into a build folder, from the rules already written there. */
export function writePagesConfig(dist: string): void {
  const rules = readRules(dist);
  writeFileSync(join(dist, "_headers"), pagesHeaders(rules));
  writeFileSync(join(dist, "_redirects"), pagesRedirects(rules));
}
