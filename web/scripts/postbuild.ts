// After `vite build` and the pre-render build of one site (ADR 0018, ADR 0058; scripts/build-sites.ts runs all three for
// each site): computes the hash of each inline script in index.html, puts the Content Security Policy into the page as a
// <meta> tag and the style sheet into a <style>, writes the page into a folder for every screen of the site (so any
// static host serves deep links) with that screen's first frame pre-rendered and its own code preloaded, and writes the
// rules a host follows: headers.json, path-headers.json, redirects.json and rewrites.json, and Cloudflare Pages' own.
import { createHash } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { assertSiteAddresses } from "../src/build-settings.ts";
import { MOVED_FROM_APP } from "../src/sites/host/paths.ts";
import { distOf, siteOf } from "../src/sites/sites.ts";
import { writePagesConfig } from "./pages-config.ts";
import {
  checkoutSources,
  contentSecurityPolicy,
  creditHeaders,
  securityHeaders,
} from "../src/security/headers.ts";

const site = siteOf(process.env);
const distName = distOf(site, process.env.IDLEO_DIST);
const dist = fileURLToPath(new URL(`../${distName}/`, import.meta.url));
const { SCREENS } = (await import(`../src/sites/${site}/screens.ts`)) as {
  SCREENS: Record<string, string | null>;
};
const addresses = assertSiteAddresses(process.env);
let html = readFileSync(`${dist}index.html`, "utf8");

const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(
  (match) => match[1] ?? "",
);
const hashes = inline.map((body) => `sha256-${createHash("sha256").update(body, "utf8").digest("base64")}`);
const csp = contentSecurityPolicy(process.env, hashes);
// The Credit page has a policy of its own, with Razorpay Checkout's origins, and is the only page that loads it
// (ADR 0039); while collecting them (IDLEO_CHECKOUT_CSP=report-only) it allows Razorpay's domains and reports what the
// strict policy would have blocked.
const CREDIT = "/credit/";
const creditCsp = contentSecurityPolicy(
  process.env,
  hashes,
  checkoutSources(process.env, process.env.IDLEO_CHECKOUT_CSP === "report-only"),
);
const strictCreditCsp = contentSecurityPolicy(process.env, hashes, checkoutSources(process.env));

if (html.includes('http-equiv="Content-Security-Policy"')) {
  throw new Error("dist/index.html already has a Content Security Policy; run vite build first");
}
html = html.replace("<head>", `<head>\n    <meta http-equiv="Content-Security-Policy" content="${csp}" />`);

// The style sheet goes into each page: the page can then paint with nothing more to fetch, and one file fewer shares the
// slow first moments of the connection with the code. Every screen uses it, and a move between screens loads no page.
const sheets = [...html.matchAll(/\s*<link rel="stylesheet"[^>]*href="\/(assets\/[^"]+\.css)"[^>]*>/g)];
if (sheets.length !== 1) {
  throw new Error(`expected one style sheet in index.html; found ${sheets.length}`);
}
const [sheetLink, sheetFile] = [sheets[0]![0], sheets[0]![1]!];
html = html.replace(sheetLink, () => `\n    <style>${readFileSync(`${dist}${sheetFile}`, "utf8")}</style>`);
rmSync(`${dist}${sheetFile}`);

// The main script asks first, ahead of the fonts: the first request after the page takes the page's open connection, which
// is a round trip ahead of a new one, and this is by far the largest file. Module scripts don't block the page, so this
// changes nothing else.
const mainScript = html.match(
  /\s*<script type="module" crossorigin src="\/assets\/shell-[^"]+"><\/script>/,
)?.[0];
if (!mainScript) {
  throw new Error("no main script in index.html");
}
html = html
  .replace(mainScript, "")
  .replace(/<meta charset="utf-8" \/>/, (charset) => `${charset}${mainScript}`);

// Open the connections the first API call and token refresh will need while the scripts download.
const env = process.env;
const origins = [
  env.VITE_API_URL,
  env.VITE_AUTH === "firebase" ? "https://securetoken.googleapis.com" : env.VITE_OIDC_ISSUER,
].flatMap((url) => (url ? [new URL(url).origin] : []));
html = html.replace(
  "</head>",
  `${origins.map((origin) => `    <link rel="preconnect" href="${origin}" crossorigin>`).join("\n")}\n  </head>`,
);

// Vite's manifest: each source module's chunk and the chunks it imports statically.
type Chunk = { file: string; imports?: string[] };
const manifest = JSON.parse(readFileSync(`${dist}.vite/manifest.json`, "utf8")) as Record<string, Chunk>;
const preloaded = new Set([...html.matchAll(/href="\/(assets\/[^"]+\.js)"/g)].map((match) => match[1]!));
function chunks(key: string, seen = new Set<string>()): Set<string> {
  const chunk = manifest[key];
  if (chunk && !seen.has(chunk.file)) {
    seen.add(chunk.file);
    (chunk.imports ?? []).forEach((imported) => chunks(imported, seen));
  }
  return seen;
}

const { render } = (await import(new URL(`../${distName}-ssr/prerender.js`, import.meta.url).href)) as {
  render: (path: string) => Promise<string>;
};
const withFrame = async (page: string, path: string) =>
  page.replace('<div id="root"></div>', `<div id="root">${await render(path)}</div>`);

for (const [path, module] of Object.entries(SCREENS)) {
  const own = [...(module ? chunks(module) : [])].filter((file) => !preloaded.has(file));
  if (module && !manifest[module]) {
    throw new Error(`no chunk for ${module} in the build manifest`);
  }
  const links = own.map((file) => `    <link rel="modulepreload" crossorigin href="/${file}">`).join("\n");
  mkdirSync(`${dist}${path}`, { recursive: true });
  const linked = links ? html.replace("</head>", `${links}\n  </head>`) : html;
  const page = path === CREDIT ? linked.replace(`content="${csp}"`, `content="${creditCsp}"`) : linked;
  writeFileSync(`${dist}${path}index.html`, await withFrame(page, path));
}
writeFileSync(`${dist}404.html`, await withFrame(html, "/not-a-screen/"));
// Addresses with something after a screen's folder (a link's id, /link/<id>/) are answered with that screen's page, by
// a rewrite where the host has the rule (scripts/serve.ts reads this file; docs/runbooks/deployment.md names the rule).
const rewrites = SCREENS["/link/"] ? { "/link/": "/link/index.html" } : {};
// The renter app's old hosting addresses, permanently to the host site's (ADR 0058), query and all.
const redirects =
  site === "app"
    ? Object.fromEntries(Object.entries(MOVED_FROM_APP).map(([from, to]) => [from, `${addresses.host}${to}`]))
    : {};
writeFileSync(`${dist}rewrites.json`, `${JSON.stringify(rewrites, null, 2)}\n`);
writeFileSync(`${dist}redirects.json`, `${JSON.stringify(redirects, null, 2)}\n`);
writeFileSync(`${dist}headers.json`, `${JSON.stringify(securityHeaders(csp), null, 2)}\n`);
// The headers of a page that has its own, instead of headers.json's: the Credit page (docs/runbooks/deployment.md).
writeFileSync(
  `${dist}path-headers.json`,
  `${JSON.stringify(SCREENS[CREDIT] ? { [CREDIT]: creditHeaders(process.env, creditCsp, strictCreditCsp) } : {}, null, 2)}\n`,
);
writePagesConfig(dist);
// Source maps are never served (ADR 0036): every one leaves the build for <dist>-sourcemaps/, which the control plane
// reads to name a JavaScript error's file and line. A build made to debug with them (IDLEO_SOURCEMAP=1) keeps them.
const maps = fileURLToPath(new URL(`../${distName}-sourcemaps/`, import.meta.url));
let moved = 0;
if (process.env.IDLEO_SOURCEMAP !== "1") {
  rmSync(maps, { recursive: true, force: true });
  mkdirSync(`${maps}assets`, { recursive: true });
  for (const file of readdirSync(`${dist}assets`)) {
    if (file.endsWith(".map")) {
      renameSync(`${dist}assets/${file}`, `${maps}assets/${file}`);
      moved += 1;
    }
  }
  const left = readdirSync(dist, { recursive: true }).filter((file) => String(file).endsWith(".map"));
  if (left.length > 0) {
    throw new Error(`source maps left in the build, which would be served: ${left.join(", ")}`);
  }
}
console.log(
  `postbuild: the ${site} site, ${Object.keys(SCREENS).length} screens, ${hashes.length} inline script hash(es), ${moved} source maps kept apart, CSP: ${csp}`,
);
