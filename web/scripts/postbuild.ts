// After `vite build` and the pre-render build (ADR 0018): computes the hash of each inline script in index.html,
// puts the Content Security Policy into the page as a <meta> tag, writes the page into a folder for every screen
// (so any static host serves deep links) with that screen's first frame pre-rendered and its own code preloaded,
// and writes dist/headers.json, the headers a host should send.
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { SCREENS, SIGNED_IN_SCREENS } from "../src/screens.ts";
import { contentSecurityPolicy, securityHeaders } from "../src/security/headers.ts";

const dist = fileURLToPath(new URL(`../${process.env.IDLEO_DIST ?? "dist"}/`, import.meta.url));
let html = readFileSync(`${dist}index.html`, "utf8");

const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(
  (match) => match[1] ?? "",
);
const hashes = inline.map((body) => `sha256-${createHash("sha256").update(body, "utf8").digest("base64")}`);
const csp = contentSecurityPolicy(process.env, hashes);

if (html.includes('http-equiv="Content-Security-Policy"')) {
  throw new Error("dist/index.html already has a Content Security Policy; run vite build first");
}
html = html.replace("<head>", `<head>\n    <meta http-equiv="Content-Security-Policy" content="${csp}" />`);

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

const ADAPTERS = ["src/auth/firebase.ts", "src/auth/oidc.ts"];
const adapter = ADAPTERS.flatMap((module) => (manifest[module] ? [...chunks(module)] : []));
if (adapter.length === 0) {
  throw new Error("no sign-in adapter chunk in the build manifest");
}

const { render } = (await import(new URL("../dist-ssr/prerender.js", import.meta.url).href)) as {
  render: (path: string) => Promise<string>;
};
const withFrame = async (page: string, path: string) =>
  page.replace('<div id="root"></div>', `<div id="root">${await render(path)}</div>`);

for (const [path, module] of Object.entries(SCREENS)) {
  const own = [
    ...(module ? chunks(module) : []),
    ...(SIGNED_IN_SCREENS.includes(path) ? adapter : []),
  ].filter((file, index, all) => !preloaded.has(file) && all.indexOf(file) === index);
  if (module && !manifest[module]) {
    throw new Error(`no chunk for ${module} in the build manifest`);
  }
  const links = own.map((file) => `    <link rel="modulepreload" crossorigin href="/${file}">`).join("\n");
  mkdirSync(`${dist}${path}`, { recursive: true });
  const page = links ? html.replace("</head>", `${links}\n  </head>`) : html;
  writeFileSync(`${dist}${path}index.html`, await withFrame(page, path));
}
writeFileSync(`${dist}404.html`, await withFrame(html, "/not-a-screen/"));
// Addresses with something after a screen's folder (a link's id, /link/<id>/) are answered with that screen's page, by
// a rewrite where the host has the rule (scripts/serve.ts reads this file; docs/runbooks/deployment.md names the rule).
writeFileSync(`${dist}rewrites.json`, `${JSON.stringify({ "/link/": "/link/index.html" }, null, 2)}\n`);
writeFileSync(`${dist}headers.json`, `${JSON.stringify(securityHeaders(csp), null, 2)}\n`);
console.log(
  `postbuild: ${Object.keys(SCREENS).length} screens, ${hashes.length} inline script hash(es), CSP: ${csp}`,
);
