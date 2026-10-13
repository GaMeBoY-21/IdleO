// The first-load JavaScript budget (ADR 0018, web/DESIGN.md "Speed is a feature"): what the signed-in home
// downloads before it can show the renter's credit. That's the entry script and everything it imports
// statically, plus the sign-in adapter and its imports, which the home needs for a token before its first API
// call. Counted gzipped, and failed above the budget. A screen loaded later (credit, sign-in) doesn't count.
import { readdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";

export const BUDGET_KB = 150;

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

const files = closure([...entries, `assets/${adapter[0]}`]);
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
