// What each site's development server optimises up front (src/sites/sites.ts, lazyDeps; vite.config.ts): every direct
// dependency that only its lazily loaded chunks hold. One the list misses is found by the server only when a screen asks
// for it, and it re-optimises then, mid-session. Read from the source modules of every chunk the build wrote
// (.vite/chunk-modules.json): the main script's are optimised from the start; the rest must be named. The sign-in
// adapters' packages (SIGN_IN_DEPS) count for every site.
//
//   node scripts/dev-deps.ts      checks every site's build folder (IDLEO_DIST as scripts/build-sites.ts reads it)
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { distOf, SIGN_IN_DEPS, SITE_FACTS, SITES, type SiteName } from "../src/sites/sites.ts";

/** The package a module in node_modules belongs to, or null for one of ours. */
export function packageOf(module: string): string | null {
  const at = module.lastIndexOf("node_modules/");
  if (at < 0) {
    return null;
  }
  const [first = "", second = ""] = module.slice(at + "node_modules/".length).split("/");
  return first.startsWith("@") ? `${first}/${second}` : first;
}

/** The package an import names: `zod/mini` is zod's, `@hookform/resolvers/zod` @hookform/resolvers'. */
export function packageNamed(specifier: string): string {
  const [first = "", second = ""] = specifier.split("/");
  return first.startsWith("@") ? `${first}/${second}` : first;
}

/** The direct dependencies only the lazily loaded chunks hold that `named` doesn't name, sorted. */
export function unnamed(
  chunks: Record<string, readonly string[]>,
  dependencies: ReadonlySet<string>,
  named: readonly string[],
): string[] {
  const inMain = new Set<string>();
  const lazily = new Set<string>();
  for (const [chunk, modules] of Object.entries(chunks)) {
    for (const module of modules) {
      const name = packageOf(module);
      if (name !== null && dependencies.has(name)) {
        (/(^|\/)shell-[^/]+\.js$/.test(chunk) ? inMain : lazily).add(name);
      }
    }
  }
  const covered = new Set(named.map(packageNamed));
  return [...lazily].filter((name) => !inMain.has(name) && !covered.has(name)).sort();
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const manifest = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")) as {
    dependencies: Record<string, string>;
  };
  const dependencies = new Set(Object.keys(manifest.dependencies));
  let failed = false;
  for (const site of SITES as readonly SiteName[]) {
    const dist = fileURLToPath(new URL(`../${distOf(site, process.env.IDLEO_DIST)}/`, import.meta.url));
    const chunks = JSON.parse(readFileSync(`${dist}.vite/chunk-modules.json`, "utf8")) as Record<
      string,
      string[]
    >;
    const missing = unnamed(chunks, dependencies, [
      ...SITE_FACTS[site].lazyDeps,
      ...Object.values(SIGN_IN_DEPS).flat(),
    ]);
    if (missing.length > 0) {
      console.error(
        `the ${site} site's lazily loaded screens import ${missing.join(", ")}, which its development server doesn't ` +
          "optimise up front: add them to its lazyDeps (src/sites/sites.ts)",
      );
      failed = true;
    } else {
      console.log(`the ${site} site: every lazily loaded package is optimised up front in development`);
    }
  }
  process.exit(failed ? 1 : 0);
}
