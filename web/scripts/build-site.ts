// The website's own output (ADR 0029): writes the public pages into dist-website/, apart from the app's dist/. They are
// plain HTML drawn at build time by src/site/render.tsx (built apart from the app, vite.site.config.ts), linking the
// style sheet and, on the two pages with something live, one small script. Nothing of the app is on them. Also writes
// the 404 page, sitemap.xml, robots.txt, the headers a host should send, and the redirect from the old landing page.
import { createHash } from "node:crypto";
import { cpSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { siteSettingsProblems } from "../src/build-settings.ts";
import { brand } from "../src/brand/brand.ts";
import { themeBootScript } from "../src/design/theme.ts";
import { contentSecurityPolicy, securityHeaders } from "../src/security/headers.ts";
import type { PageDef, SiteContext } from "../src/site/context.ts";
import { OLD_LANDING, LANDING, type EntryLinks } from "../src/site/paths.ts";
import { siteContext } from "./site/context.ts";

const here = (path: string) => fileURLToPath(new URL(`../${path}`, import.meta.url));
const dist = here(`${process.env.IDLEO_WEBSITE_DIST ?? "dist-website"}/`);
const siteDist = here(`${process.env.IDLEO_SITE_DIST ?? "dist-site"}/`);

type Chunk = { file: string; css?: string[]; imports?: string[]; isEntry?: boolean };
const manifest = JSON.parse(readFileSync(`${siteDist}.vite/manifest.json`, "utf8")) as Record<string, Chunk>;

const env = process.env;
// A deployed build needs both addresses; a local one has the local stack's (start.sh): the website at :3002, the app at :3000.
const problems = siteSettingsProblems(env);
if (problems.length > 0) {
  throw new Error(`Refusing to build the website: ${problems.join("; ")}`);
}
const siteUrl = (env.IDLEO_SITE_URL ?? "http://localhost:3002").replace(/\/$/, "");
const appUrl = (env.IDLEO_APP_URL ?? "http://localhost:3000").replace(/\/$/, "");
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

const ISLANDS = {
  pricing: "src/site/islands/pricing.ts",
  earn: "src/site/islands/earn.ts",
  contact: "src/site/islands/contact.ts",
  grievance: "src/site/islands/grievance.ts",
  vitals: "src/site/islands/vitals.ts",
  theme: "src/site/islands/theme.ts",
} as const;

const sha = (body: string) => `sha256-${createHash("sha256").update(body, "utf8").digest("base64")}`;
// The one inline script is the theme's (src/design/theme.ts), which the app's pages carry too, so a chosen theme never
// flashes the other first. The pages allow its hash and nothing else; they talk to the API only for the live prices.
const themeBoot = themeBootScript;
const csp = contentSecurityPolicy(
  { ...env, VITE_AUTH: undefined, VITE_STORAGE_URL: undefined, VITE_OIDC_ISSUER: undefined },
  [sha(themeBoot)],
);
const escape = (text: string) => text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");

function document(page: PageDef, body: string): string {
  const url = `${siteUrl}${page.path}`;
  // Every page reports what real visits meet (ADR 0036), after it has loaded; Pricing and Earn have their own script too, and every page the Appearance switch's.
  const loaded = [...page.islands, "vitals" as const, "theme" as const].map((island) =>
    script(ISLANDS[island]),
  );
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
    <meta property="og:image" content="${siteUrl}/og-image.png" />
    <meta property="og:image:width" content="1200" />
    <meta property="og:image:height" content="630" />
    <meta property="og:image:alt" content="${escape(`${brand.name}: ${brand.share}`)}" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="theme-color" media="(prefers-color-scheme: light)" content="#F4F3FA" />
    <meta name="theme-color" media="(prefers-color-scheme: dark)" content="#0C0A1F" />
    <link rel="icon" href="/icon.svg" type="image/svg+xml" />
    <link rel="icon" href="/favicon.ico" sizes="48x48" />
    <link rel="apple-touch-icon" href="/apple-touch-icon.png" />
    <link rel="preload" href="/fonts/inter-400-700.woff2" as="font" type="font/woff2" crossorigin />
    <link rel="preload" href="/fonts/archivo-125-900-lower.woff2" as="font" type="font/woff2" crossorigin />
    <link rel="preload" href="/fonts/archivo-125-900.woff2" as="font" type="font/woff2" crossorigin />
    <script>${themeBoot}</script>
    <link rel="stylesheet" href="/${stylesheet}" />
${preloads.map((file) => `    <link rel="modulepreload" href="/${file}" />`).join("\n")}
  </head>
  <body data-api="${escape(env.VITE_API_URL ?? "")}" data-app="${escape(appUrl)}">
${body}
${loaded.map((one) => `    <script type="module" src="/${one.entry}"></script>`).join("\n")}
  </body>
</html>
`;
}

const context = siteContext(env.VITE_API_URL, appUrl);

const { pages, renderPage, notFoundPage } = (await import(
  new URL("../dist-site-ssr/render.js", import.meta.url).href
)) as {
  pages: (context: SiteContext) => PageDef[];
  renderPage: (page: PageDef, links: EntryLinks) => string;
  notFoundPage: () => PageDef;
};

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
cpSync(`${siteDist}site-assets`, `${dist}site-assets`, { recursive: true });
// Source maps are never served (ADR 0036): they go to <dist>-sourcemaps/, which the control plane reads to name a
// JavaScript error's file and line.
const maps = here(`${process.env.IDLEO_WEBSITE_DIST ?? "dist-website"}-sourcemaps/site-assets/`);
rmSync(maps, { recursive: true, force: true });
mkdirSync(maps, { recursive: true });
for (const file of readdirSync(`${dist}site-assets`)) {
  if (file.endsWith(".map")) {
    renameSync(`${dist}site-assets/${file}`, `${maps}${file}`);
  }
}
// The font and the icon are the app's own files, shared by both builds.
cpSync(here("public"), dist, { recursive: true });
const all = pages(context);
for (const page of all) {
  mkdirSync(`${dist}${page.path}`, { recursive: true });
  writeFileSync(`${dist}${page.path}index.html`, document(page, renderPage(page, context.links)));
}
// A path the website doesn't have (served with a 404).
writeFileSync(
  `${dist}404.html`,
  document(notFoundPage(), renderPage(notFoundPage(), context.links)).replace(
    "</title>",
    '</title>\n    <meta name="robots" content="noindex" />',
  ),
);
// The old landing page: a permanent redirect where the host can send one (redirects.json, which scripts/serve.ts honours),
// and a page that sends the browser on where it can't, so an old link keeps working.
mkdirSync(`${dist}${OLD_LANDING}`, { recursive: true });
writeFileSync(
  `${dist}${OLD_LANDING}index.html`,
  `<!doctype html>\n<html lang="en-IN">\n  <head>\n    <meta charset="utf-8" />\n    <meta http-equiv="refresh" content="0; url=${LANDING}" />\n    <meta name="robots" content="noindex" />\n    <title>Moved</title>\n    <link rel="canonical" href="${siteUrl}${LANDING}" />\n  </head>\n  <body>\n    <p><a href="${LANDING}">This page has moved.</a></p>\n  </body>\n</html>\n`,
);
writeFileSync(`${dist}redirects.json`, `${JSON.stringify({ [OLD_LANDING]: LANDING }, null, 2)}\n`);
writeFileSync(`${dist}headers.json`, `${JSON.stringify(securityHeaders(csp), null, 2)}\n`);
const leftMaps = readdirSync(dist, { recursive: true }).filter((file) => String(file).endsWith(".map"));
if (leftMaps.length > 0) {
  throw new Error(`source maps left in the website, which would be served: ${leftMaps.join(", ")}`);
}

writeFileSync(
  `${dist}sitemap.xml`,
  `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${all
    .map((page) => `  <url><loc>${siteUrl}${page.path}</loc></url>`)
    .join("\n")}\n</urlset>\n`,
);
writeFileSync(`${dist}robots.txt`, `User-agent: *\nAllow: /\n\nSitemap: ${siteUrl}/sitemap.xml\n`);
console.log(`build-site: ${all.length} public pages, ${context.docs.length} of them docs`);
