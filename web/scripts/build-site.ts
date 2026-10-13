// After the app's build (ADR 0029): writes the public pages into dist/. They are plain HTML drawn at build time by
// src/site/render.tsx (built apart from the app, vite.site.config.ts), linking the style sheet and, on the two pages
// with something live, one small script. Nothing of the app is on them. Also writes sitemap.xml and robots.txt.
import { createHash } from "node:crypto";
import { cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { brand } from "../src/brand/brand.ts";
import { contentSecurityPolicy } from "../src/security/headers.ts";
import type { PageDef, SiteContext } from "../src/site/context.ts";
import { loadServices, workedExamples } from "./site/catalog.ts";
import { buildDocs } from "./site/docs.ts";

const here = (path: string) => fileURLToPath(new URL(`../${path}`, import.meta.url));
const root = (path: string) => fileURLToPath(new URL(`../../${path}`, import.meta.url));
const dist = here(`${process.env.IDLEO_DIST ?? "dist"}/`);
const siteDist = here(`${process.env.IDLEO_SITE_DIST ?? "dist-site"}/`);

type Chunk = { file: string; css?: string[]; imports?: string[]; isEntry?: boolean };
const manifest = JSON.parse(readFileSync(`${siteDist}.vite/manifest.json`, "utf8")) as Record<string, Chunk>;

const env = process.env;
const siteUrl = (env.IDLEO_SITE_URL ?? "http://localhost:3000").replace(/\/$/, "");
const stylesheet = manifest["src/site/site.css"]?.file;
if (!stylesheet) {
  throw new Error("no style sheet for the public pages in the site build's manifest");
}

/** A script entry and the chunks it imports statically, for the page to load and preload. */
function script(source: string): { entry: string; preload: string[] } {
  const chunk = manifest[source];
  if (!chunk) {
    throw new Error(`no ${source} in the site build's manifest`);
  }
  const preload: string[] = [];
  const walk = (key: string) => {
    for (const imported of manifest[key]?.imports ?? []) {
      if (!preload.includes(manifest[imported]!.file)) {
        preload.push(manifest[imported]!.file);
        walk(imported);
      }
    }
  };
  walk(source);
  return { entry: chunk.file, preload };
}

const ISLANDS = { pricing: "src/site/islands/pricing.ts", earn: "src/site/islands/earn.ts" } as const;

const sha = (body: string) => `sha256-${createHash("sha256").update(body, "utf8").digest("base64")}`;
// The one inline script is the theme's (src/design/theme.ts), which the app's pages carry too. Taken from the app's own
// page, byte for byte, so its hash is the one the headers (dist/headers.json) already allow.
const themeBoot = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/.exec(
  readFileSync(`${dist}index.html`, "utf8"),
)?.[1];
if (!themeBoot) {
  throw new Error("no inline theme script in the app's index.html; build the app first");
}
const csp = contentSecurityPolicy(env, [sha(themeBoot)]);
const escape = (text: string) => text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");

function document(page: PageDef, body: string): string {
  const url = `${siteUrl}${page.path}`;
  const loaded = page.islands.map((island) => script(ISLANDS[island]));
  const preloads = [...new Set(loaded.flatMap((one) => one.preload))];
  return `<!doctype html>
<html lang="en-IN">
  <head>
    <meta charset="utf-8" />
    <meta http-equiv="Content-Security-Policy" content="${csp}" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="referrer" content="strict-origin-when-cross-origin" />
    <title>${escape(page.title)}</title>
    <meta name="description" content="${escape(page.description)}" />
    <link rel="canonical" href="${url}" />
    <meta property="og:site_name" content="${escape(brand.name)}" />
    <meta property="og:type" content="website" />
    <meta property="og:locale" content="en_IN" />
    <meta property="og:title" content="${escape(page.title)}" />
    <meta property="og:description" content="${escape(page.description)}" />
    <meta property="og:url" content="${url}" />
    <meta name="twitter:card" content="summary" />
    <meta name="theme-color" media="(prefers-color-scheme: light)" content="#F7F7F9" />
    <meta name="theme-color" media="(prefers-color-scheme: dark)" content="#0F0F14" />
    <link rel="icon" href="/icon.svg" type="image/svg+xml" />
    <link rel="preload" href="/fonts/inter-4.1-subset.woff2" as="font" type="font/woff2" crossorigin />
    <script>${themeBoot}</script>
    <link rel="stylesheet" href="/${stylesheet}" />
${preloads.map((file) => `    <link rel="modulepreload" href="/${file}" />`).join("\n")}
  </head>
  <body>
${body}
${loaded.map((one) => `    <script type="module" src="/${one.entry}"></script>`).join("\n")}
  </body>
</html>
`;
}

const catalog = root("templates/catalog.yaml");
const development = root("templates/development.yaml");
const services = loadServices(catalog, development);
const context: SiteContext = {
  apiUrl: env.VITE_API_URL ? new URL(env.VITE_API_URL).origin : null,
  services,
  examples: workedExamples(catalog, development),
  docs: buildDocs({
    docsDir: root("docs/site"),
    cliMarkdown: readFileSync(root("docs/site/generated/cli.md"), "utf8"),
    openapiPath: root("api/openapi.yaml"),
    services,
  }),
};

const { pages, renderPage } = (await import(new URL("../dist-site-ssr/render.js", import.meta.url).href)) as {
  pages: (context: SiteContext) => PageDef[];
  renderPage: (page: PageDef) => string;
};

cpSync(`${siteDist}site-assets`, `${dist}site-assets`, { recursive: true });
const all = pages(context);
for (const page of all) {
  mkdirSync(`${dist}${page.path}`, { recursive: true });
  writeFileSync(`${dist}${page.path}index.html`, document(page, renderPage(page)));
}

writeFileSync(
  `${dist}sitemap.xml`,
  `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${all
    .map((page) => `  <url><loc>${siteUrl}${page.path}</loc></url>`)
    .join("\n")}\n</urlset>\n`,
);
writeFileSync(`${dist}robots.txt`, `User-agent: *\nAllow: /\n\nSitemap: ${siteUrl}/sitemap.xml\n`);
console.log(`build-site: ${all.length} public pages, ${context.docs.length} of them docs`);
