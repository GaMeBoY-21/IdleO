// Every admin write, pressed from the screen (ADR 0057). Reads the permission matrix for every POST, PUT and DELETE, and
// what the browser tests recorded (web/e2e/support.ts, in admin-writes/), and fails listing any write no test has made
// from the UI. A call the end-to-end scripts make with admin-call.py doesn't count: only a page's own requests do.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url));

export function writesInTheMatrix(csv: string): string[] {
  return csv
    .split("\n")
    .slice(1)
    .map((line) => line.split(",")[0] ?? "")
    .filter((endpoint) => /^(POST|PUT|DELETE|PATCH) /.test(endpoint));
}

export function uncovered(writes: string[], recorded: { method: string; path: string }[]): string[] {
  const seen = new Set(recorded.map((call) => `${call.method} ${call.path}`));
  return writes.filter((write) => {
    const [method, template] = write.split(" ") as [string, string];
    // A literal path beats a template's variable: /queue/bulk/preview is never /queue/bulk/{id}'s.
    const pattern = new RegExp(`^${method} ${template.replace(/\{[^}]+\}/g, "[^/]+")}$`);
    const literal = !template.includes("{");
    return ![...seen].some((call) => {
      if (!pattern.test(call)) {
        return false;
      }
      // /queue/{id}/claim mustn't count /queue/bulk/... and the like: a literal write has its own line.
      return literal || !writes.includes(call);
    });
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const folder = process.argv[2] ?? here("../admin-writes/");
  const writes = writesInTheMatrix(
    readFileSync(here("../../control-plane/src/test/resources/admin-permission-matrix.csv"), "utf8"),
  );
  const recorded = existsSync(folder)
    ? readdirSync(folder, { recursive: true, encoding: "utf8" })
        .filter((file) => file.endsWith(".jsonl"))
        .flatMap((file) => readFileSync(`${folder}/${file}`, "utf8").trim().split("\n"))
        .filter(Boolean)
        .map((line) => JSON.parse(line) as { method: string; path: string })
    : [];
  const missing = uncovered(writes, recorded);
  console.log(
    `admin UI coverage: ${writes.length - missing.length} of ${writes.length} writes pressed from a screen`,
  );
  if (missing.length > 0) {
    console.error(`no browser test makes these from the UI:\n  ${missing.join("\n  ")}`);
    process.exit(1);
  }
}
