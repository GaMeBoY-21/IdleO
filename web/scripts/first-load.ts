// The first-load JavaScript budget (ADR 0018, web/DESIGN.md "Speed is a feature"): what the signed-in home
// downloads before it can show the renter's credit. That's the entry script and everything it imports
// statically, the home's own chunk, plus the sign-in adapter and its imports, which the home needs for a token before its
// first API call. Counted gzipped, and failed above the budget. A screen loaded later (credit, sign-in) doesn't count.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import { describeGrowth, tidyModuleSizes } from "./main-script-growth.ts";

export const BUDGET_KB = 150;
/**
 * The main script before a screen paints (ADR 0049): today's size plus 1 KB, with Brotli as it's served. More than this and
 * Session and Credit go up a whole round, to about 1,802 ms, so growth fails here in seconds rather than in the Lighthouse
 * job. Raising it is the product owner's say (web/README.md, "The budgets"); IDLEO_ACCEPT_MAIN_SCRIPT=1 then records which
 * modules it holds, against which the next failure names what grew.
 */
export const MAIN_SCRIPT_BUDGET_KB = 96.6;
const MAIN_SCRIPT_MODULES = fileURLToPath(new URL("./main-script-modules.json", import.meta.url));

const dist = fileURLToPath(new URL(`../${process.env.IDLEO_DIST ?? "dist"}/`, import.meta.url));
const read = (file: string) => readFileSync(`${dist}${file}`, "utf8");
const html = read("index.html");

const entries = [...html.matchAll(/<script[^>]*type="module"[^>]*src="\/([^"]+)"/g)].map(
  (match) => match[1]!,
);
// The sign-in adapter is the one chunk that src/auth/index.ts imports dynamically: Firebase, or the local mock.
const adapter = readdirSync(`${dist}assets`)
  .filter((file) => file.endsWith(".js"))
  .flatMap((file) =>
    [...read(`assets/${file}`).matchAll(/import\(["'`]\.\/((?:firebase|oidc)-[\w-]+\.js)["'`]\)/g)].map(
      (match) => match[1]!,
    ),
  )
  .filter((file, index, all) => all.indexOf(file) === index && !file.startsWith("firebase-sign-in"));
if (entries.length === 0 || adapter.length !== 1) {
  throw new Error(
    `expected an entry script and one sign-in adapter chunk; found ${entries.join(", ")} and ${adapter.join(", ")}`,
  );
}

/** A chunk and everything it imports statically, by file. */
function closure(start: string[], seen = new Set<string>()): Set<string> {
  for (const file of start) {
    if (seen.has(file)) {
      continue;
    }
    seen.add(file);
    const code = read(file);
    const imports = [
      ...code.matchAll(/(?:import|export)\s*(?:[\w*{}\s,$]+from\s*)?["'`]\.\/([\w.-]+\.js)["'`]/g),
    ].map((match) => `assets/${match[1]}`);
    closure(imports, seen);
  }
  return seen;
}

const manifest = JSON.parse(read(".vite/manifest.json")) as Record<string, { file: string }>;
const home = manifest["src/features/home/Home.tsx"]?.file;
if (!home) {
  throw new Error("no chunk for the home screen in the build manifest");
}
const files = closure([...entries, home, `assets/${adapter[0]}`]);
const rows = [...files].map((file) => ({ file, kb: gzipSync(read(file), { level: 9 }).length / 1024 }));
const total = rows.reduce((sum, row) => sum + row.kb, 0);

for (const row of rows.sort((a, b) => b.kb - a.kb)) {
  console.log(`${row.kb.toFixed(1).padStart(6)} KB  ${row.file}`);
}
console.log(`${total.toFixed(1).padStart(6)} KB  first load of the signed-in home (budget ${BUDGET_KB} KB)`);
// The admin console is its own chunk, loaded only on an /admin/ address (ADR 0027): nothing a renter loads first may
// hold its code.
const holdingAdmin = [...files].filter((file) => read(file).includes("/admin/v1/"));
if (holdingAdmin.length > 0) {
  console.error(`the admin console's code is in the first load: ${holdingAdmin.join(", ")}`);
  process.exit(1);
}
if (total > BUDGET_KB) {
  console.error(`first-load JavaScript is ${total.toFixed(1)} KB, over the ${BUDGET_KB} KB budget`);
  process.exit(1);
}

const mainScript = entries.find((file) => file.includes("/shell-"));
if (!mainScript) {
  throw new Error(`no main script among ${entries.join(", ")}`);
}
const mainKb =
  brotliCompressSync(read(mainScript), { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length / 1024;
console.log(
  `${mainKb.toFixed(1).padStart(6)} KB  the main script, with Brotli (budget ${MAIN_SCRIPT_BUDGET_KB} KB)`,
);
const modules = tidyModuleSizes(JSON.parse(read(".vite/main-script-modules.json")) as Record<string, number>);
if (process.env.IDLEO_ACCEPT_MAIN_SCRIPT === "1") {
  writeFileSync(MAIN_SCRIPT_MODULES, `${JSON.stringify(modules, null, 2)}\n`);
  console.log(`recorded the main script's modules in ${MAIN_SCRIPT_MODULES}`);
} else if (mainKb > MAIN_SCRIPT_BUDGET_KB) {
  const before = JSON.parse(readFileSync(MAIN_SCRIPT_MODULES, "utf8")) as Record<string, number>;
  console.error(
    `the main script is ${mainKb.toFixed(1)} KB with Brotli, over its ${MAIN_SCRIPT_BUDGET_KB} KB budget (ADR 0049): another round on a slow phone.\n` +
      describeGrowth(before, modules),
  );
  process.exit(1);
}
