// The code stays apart (ADR 0058): every site's build holds only its own code and what's shared (src/sites/sites.ts,
// OWNED), read from the source modules of every chunk the build wrote (.vite/chunk-modules.json, vite.config.ts). The
// host site holds nothing of renting; the renter app nothing of hosting. The admin console is in the renter app's build
// as its own lazily loaded chunk until it has its own site; no first load holds it (scripts/first-load.ts).
//
//   node scripts/site-modules.ts      checks every site's build folder (IDLEO_DIST as scripts/build-sites.ts reads it)
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { distOf, ownerOf, SITES, type SiteName } from "../src/sites/sites.ts";

/** Whose code a site's build may hold besides its own and the shared, anywhere in it (not in a first load). */
const LAZILY: Record<SiteName, ReadonlyArray<"admin">> = { app: ["admin"], host: [] };

/** Every module in these chunks that isn't the site's own or shared, as `chunk: module (owner)`. */
export function strangers(
  site: SiteName,
  chunks: Record<string, readonly string[]>,
  { firstLoad = false }: { firstLoad?: boolean } = {},
): string[] {
  const allowed = new Set<string>([site, ...(firstLoad ? [] : LAZILY[site])]);
  return Object.entries(chunks).flatMap(([chunk, modules]) =>
    modules.flatMap((module) => {
      const owner = ownerOf(module);
      return owner !== null && !allowed.has(owner) ? [`${chunk}: ${module} (${owner})`] : [];
    }),
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let failed = false;
  for (const site of SITES) {
    const dist = fileURLToPath(new URL(`../${distOf(site, process.env.IDLEO_DIST)}/`, import.meta.url));
    const chunks = JSON.parse(readFileSync(`${dist}.vite/chunk-modules.json`, "utf8")) as Record<
      string,
      string[]
    >;
    const modules = Object.values(chunks).flat();
    const found = strangers(site, chunks);
    if (found.length > 0) {
      console.error(`the ${site} site's build holds code that isn't its own:\n${found.join("\n")}`);
      failed = true;
    } else {
      console.log(
        `the ${site} site: ${modules.length} modules in ${Object.keys(chunks).length} chunks, none another site's`,
      );
    }
  }
  process.exit(failed ? 1 : 0);
}
