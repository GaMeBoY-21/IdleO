// The Lighthouse check: the browser run (the report-only figures, interaction to next paint), then `lhci assert`, the budgets
// themselves. Both always run, so the budgets have a result even when something report-only is over; the exit is non-zero
// if either failed. `pnpm lighthouse` is this.
import { spawnSync } from "node:child_process";

const run = (args: string[]) => spawnSync("pnpm", ["exec", ...args], { stdio: "inherit" }).status === 0;

const browser = run(["playwright", "test", "--project=lighthouse"]);
const budgets = run(["lhci", "assert", "--config=lighthouserc.json"]);

console.log(`lighthouse: lhci assert, the budgets: ${budgets ? "PASS" : "FAIL"}`);
console.log(
  `lighthouse: browser run, interaction to next paint and the report-only figures: ${browser ? "PASS" : "FAIL"}`,
);
process.exit(browser && budgets ? 0 : 1);
