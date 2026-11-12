// The code stays apart (ADR 0058): every site's build holds only its own code and what's shared (src/sites/sites.ts,
// OWNED), read from the source modules of every chunk the build wrote (.vite/chunk-modules.json, vite.config.ts). The
// host site holds nothing of renting; the renter app nothing of hosting; neither anything of the console, which is the
// admin site's alone, and the admin site nothing of either.
//
//   node scripts/site-modules.ts      checks every site's build folder (IDLEO_DIST as scripts/build-sites.ts reads it)
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { distOf, ownerOf, SITES, type SiteName } from "../src/sites/sites.ts";

/** Every module in these chunks that isn't the site's own or shared, as `chunk: module (owner)`. */
export function strangers(site: SiteName, chunks: Record<string, readonly string[]>): string[] {
  return Object.entries(chunks).flatMap(([chunk, modules]) =>
    modules.flatMap((module) => {
      const owner = ownerOf(module);
      return owner !== null && owner !== site ? [`${chunk}: ${module} (${owner})`] : [];
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
    // The component gallery is a development server's alone (vite.config.ts, galleryPage): never in a build.
    const found = [
      ...strangers(site, chunks),
      ...Object.entries(chunks).flatMap(([chunk, held]) =>
        held
          .filter((module) => module.startsWith("src/gallery/"))
          .map((module) => `${chunk}: ${module} (the gallery)`),
      ),
    ];
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
