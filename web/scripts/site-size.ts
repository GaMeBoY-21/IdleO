// The public pages' first-load budget (ADR 0029, web/README.md "The budgets"): what a phone downloads before it can show
// a public page, counted gzipped: the page's HTML, its style sheet and its scripts with the chunks they import. The font
// is one cached file shared with the app and isn't counted. Failed above the budget; the apps' own is scripts/first-load.ts.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";

export const SITE_BUDGET_KB = 80;

const dist = fileURLToPath(
  new URL(`../${process.env.IDLEO_WEBSITE_DIST ?? "dist-website"}/`, import.meta.url),
);
const gz = (file: string) => gzipSync(readFileSync(file), { level: 9 }).length / 1024;

/** Every public page: the folders with an index.html that load the public style sheet. */
function pages(directory = dist, found: string[] = []): string[] {
  for (const name of readdirSync(directory)) {
    const path = `${directory}${name}`;
    if (statSync(path).isDirectory()) {
      pages(`${path}/`, found);
    } else if (name === "index.html" && readFileSync(path, "utf8").includes("/site-assets/")) {
      found.push(path);
    }
  }
  return found;
}

const rows = pages().map((file) => {
  const html = readFileSync(file, "utf8");
  const assets = [
    ...new Set([...html.matchAll(/(?:href|src)="\/(site-assets\/[^"]+)"/g)].map((match) => match[1]!)),
  ];
  const kb = gz(file) + assets.reduce((sum, asset) => sum + gz(`${dist}${asset}`), 0);
  return {
    page: file.slice(dist.length - 1, -"index.html".length),
    kb,
    scripts: assets.filter((a) => a.endsWith(".js")).length,
  };
});
if (rows.length === 0) {
  throw new Error("no public pages in dist-website/; build the website first (pnpm build:site)");
}
for (const row of rows.sort((a, b) => b.kb - a.kb)) {
  console.log(
    `${row.kb.toFixed(1).padStart(6)} KB  ${row.page}  (${row.scripts} script${row.scripts === 1 ? "" : "s"})`,
  );
}
const worst = rows.reduce((a, b) => (b.kb > a.kb ? b : a));
console.log(
  `${worst.kb.toFixed(1).padStart(6)} KB  the largest public page's first load (budget ${SITE_BUDGET_KB} KB)`,
);
if (worst.kb > SITE_BUDGET_KB) {
  console.error(`${worst.page} is ${worst.kb.toFixed(1)} KB, over the ${SITE_BUDGET_KB} KB budget`);
  process.exit(1);
}
