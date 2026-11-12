// The UI review (web/README.md "The UI review"): every screen of every site in each of its states, at 1440, 1280,
// 1024, 768 and 390 px, in both themes, as pictures a person looks through, with what e2e/ui-audit.ts found on each.
//
//   node e2e/ui-review.ts                       everything, into ../private/ui-review/<date>/
//   node e2e/ui-review.ts --widths 1440,390 --themes dark --states full,error --no-specs
//   node e2e/ui-review.ts --index-only --out <dir>             the pages again, from the pictures already there
//   node e2e/ui-review.ts --before <earlier dir> --out <dir>   also before-and-after pages, the two side by side
//
// It needs the four sites built against the fixtures' API (http://localhost:8180, which nothing answers but the
// browser's own routes) and served, and the development sign-in server: what .github/workflows/ui-review.yml sets up,
// and web/README.md says how to locally. Sites that aren't answering are served here from dist/, dist-host/,
// dist-admin/ and dist-website/. Pictures are JPEGs, at most 4,000 px tall; nothing is committed.
import { spawn, spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import type { Capture } from "./ui-review-capture";

const web = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const { values: options } = parseArgs({
  options: {
    out: { type: "string" },
    widths: { type: "string", default: "1440,1280,1024,768,390" },
    themes: { type: "string", default: "dark,light" },
    states: { type: "string", default: "full,loading,empty,error,long,many" },
    "no-specs": { type: "boolean", default: false },
    workers: { type: "string" },
    grep: { type: "string" },
    before: { type: "string" },
    "index-only": { type: "boolean", default: false },
  },
});
const today = new Date().toISOString().slice(0, 10);
const out = resolve(options.out ?? join(web, "..", "private", "ui-review", today));
const SITES = ["admin", "app", "host", "website"] as const;
const SITE_NAMES: Record<string, string> = {
  admin: "The admin site",
  app: "The renter app",
  host: "The host site",
  website: "The website",
};
const STATE_ORDER = ["full", "loading", "empty", "error", "long", "many"];

const settings = {
  API_URL: process.env.API_URL ?? "http://localhost:8180",
  SIGN_IN_URL: process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local",
  WEB_URL: process.env.WEB_URL ?? "http://localhost:3000",
  HOST_URL: process.env.HOST_URL ?? "http://localhost:3003",
  ADMIN_URL: process.env.ADMIN_URL ?? "http://localhost:3004",
  SITE_URL: process.env.SITE_URL ?? "http://localhost:3002",
};

async function answers(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2000) });
    return response.status < 500;
  } catch {
    return false;
  }
}

/** Serves each site that isn't answering from its build; returns the servers started, to stop at the end. */
async function serveSites(): Promise<ReturnType<typeof spawn>[]> {
  if (!(await answers(`${settings.SIGN_IN_URL}/.well-known/openid-configuration`))) {
    console.error(
      `ui-review: the development sign-in server isn't answering at ${settings.SIGN_IN_URL}: ` +
        "docker compose -p idleo-review -f control-plane/compose.yaml up -d sign-in",
    );
    process.exit(2);
  }
  const started: ReturnType<typeof spawn>[] = [];
  for (const [dist, address, probe] of [
    ["dist", settings.WEB_URL, "/sign-in/"],
    ["dist-host", settings.HOST_URL, "/sign-in/"],
    ["dist-admin", settings.ADMIN_URL, "/sign-in/"],
    ["dist-website", settings.SITE_URL, "/"],
  ] as const) {
    if (await answers(`${address}${probe}`)) {
      continue;
    }
    if (!existsSync(join(web, dist))) {
      console.error(
        `ui-review: nothing answers at ${address} and there's no ${dist}/ to serve: build first (web/README.md)`,
      );
      process.exit(2);
    }
    const server = spawn(process.execPath, ["scripts/serve.ts"], {
      cwd: web,
      env: { ...process.env, IDLEO_DIST: dist, PORT: new URL(address).port },
      stdio: "ignore",
    });
    started.push(server);
    const deadline = Date.now() + 15_000;
    while (!(await answers(`${address}${probe}`))) {
      if (Date.now() > deadline) {
        console.error(`ui-review: ${dist}/ didn't start at ${address}`);
        process.exit(1);
      }
      await new Promise((resolve) => setTimeout(resolve, 200));
    }
  }
  return started;
}

function runBrowser(): number {
  rmSync(join(out, "raw"), { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  const args = ["exec", "playwright", "test", "--project=ui-review-*", "--reporter=line,json"];
  if (options.workers) {
    args.push(`--workers=${options.workers}`);
  }
  if (options.grep) {
    args.push("--grep", options.grep);
  }
  const result = spawnSync("pnpm", args, {
    cwd: web,
    stdio: ["ignore", "inherit", "inherit"],
    env: {
      ...process.env,
      ...settings,
      SKIP_VISUAL: "1",
      UI_REVIEW_DIR: out,
      UI_REVIEW_WIDTHS: options.widths,
      UI_REVIEW_THEMES: options.themes,
      UI_REVIEW_STATES: options.states,
      UI_REVIEW_SPECS: options["no-specs"] ? "0" : "1",
      PLAYWRIGHT_JSON_OUTPUT_NAME: join(out, "report.json"),
    },
  });
  return result.status ?? 1;
}

/** Moves the pictures from raw/<site>/ to <site>/ and reads every capture's record. */
function collect(): Capture[] {
  const captures: Capture[] = [];
  for (const site of SITES) {
    const raw = join(out, "raw", site);
    const folder = join(out, site);
    mkdirSync(folder, { recursive: true });
    if (existsSync(raw)) {
      for (const file of readdirSync(raw)) {
        renameSync(join(raw, file), join(folder, file));
      }
    }
    for (const file of readdirSync(folder).filter((name) => name.endsWith(".json"))) {
      captures.push(JSON.parse(readFileSync(join(folder, file), "utf8")) as Capture);
    }
  }
  rmSync(join(out, "raw"), { recursive: true, force: true });
  return captures;
}

const escape = (value: string) =>
  value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");

const STYLE = `
:root { color-scheme: light dark; --bg: #f4f3fa; --panel: #fff; --text: #0c0a1f; --muted: #4a4668; --line: #e4e2f0;
  --p0: #c0262d; --p1: #9a3d06; --p2: #4a4668; }
@media (prefers-color-scheme: dark) { :root { --bg: #0c0a1f; --panel: #17142f; --text: #fff; --muted: #b9b5da;
  --line: #2a2650; --p0: #ff7a7a; --p1: #ffb27f; --p2: #b9b5da; } }
* { box-sizing: border-box; }
body { margin: 0; font: 14px/1.5 system-ui, sans-serif; background: var(--bg); color: var(--text); }
header { position: sticky; top: 0; z-index: 1; background: var(--panel); border-bottom: 1px solid var(--line);
  padding: 12px 24px; display: flex; gap: 24px; flex-wrap: wrap; align-items: center; }
header h1 { font-size: 18px; margin: 0 16px 0 0; }
fieldset { border: 0; padding: 0; margin: 0; display: flex; gap: 8px; align-items: center; }
legend { float: left; margin-right: 8px; color: var(--muted); }
main { padding: 24px; max-width: 2200px; }
section { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 16px 20px; margin: 0 0 24px; }
section h2 { font-size: 16px; margin: 0 0 4px; }
.path { color: var(--muted); font-family: ui-monospace, monospace; font-size: 12px; }
.grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); margin-top: 12px; }
figure { margin: 0; }
figure img { width: 100%; height: 260px; object-fit: cover; object-position: top; border: 1px solid var(--line);
  border-radius: 8px; background: var(--bg); }
figcaption { font-size: 12px; color: var(--muted); }
.p0 { color: var(--p0); font-weight: 700; } .p1 { color: var(--p1); font-weight: 600; } .p2 { color: var(--p2); }
details { margin-top: 8px; } summary { cursor: pointer; }
ul.problems { margin: 4px 0 0; padding-left: 20px; } ul.problems li { margin: 2px 0; }
table { border-collapse: collapse; } td, th { padding: 4px 12px; border-bottom: 1px solid var(--line); text-align: left; }
td.n { text-align: right; font-variant-numeric: tabular-nums; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
[hidden] { display: none !important; }
`;

/** The filters at the top of a site's page: width, theme and state, kept in the address. */
const FILTERS = (widths: number[], states: string[]) => `
<fieldset><legend>Theme</legend>${["dark", "light"]
  .map(
    (theme, i) =>
      `<label><input type="radio" name="theme" value="${theme}"${i === 0 ? " checked" : ""}> ${theme}</label>`,
  )
  .join("")}</fieldset>
<fieldset><legend>Width</legend><label><input type="radio" name="width" value="all" checked> all</label>${widths
  .map((width) => `<label><input type="radio" name="width" value="${width}"> ${width}</label>`)
  .join("")}</fieldset>
<fieldset><legend>State</legend><label><input type="radio" name="state" value="all" checked> all</label>${states
  .map(
    (state) => `<label><input type="radio" name="state" value="${escape(state)}"> ${escape(state)}</label>`,
  )
  .join("")}</fieldset>
<script>
const pick = (name) => document.querySelector('input[name="' + name + '"]:checked').value;
function apply() {
  const theme = pick("theme"), width = pick("width"), state = pick("state");
  for (const figure of document.querySelectorAll("figure[data-theme]")) {
    figure.hidden = figure.dataset.theme !== theme || (width !== "all" && figure.dataset.width !== width) ||
      (state !== "all" && figure.dataset.state !== state);
  }
}
document.addEventListener("change", apply);
apply();
</script>`;

const rank = (state: string) => {
  const at = STATE_ORDER.indexOf(state);
  return at === -1 ? STATE_ORDER.length : at;
};

function problemList(captures: Capture[]): string {
  const merged = new Map<string, { severity: string; rule: string; detail: string; where: Set<string> }>();
  for (const capture of captures) {
    for (const problem of capture.problems) {
      const key = `${problem.rule}|${problem.detail}`;
      const seen = merged.get(key) ?? { ...problem, where: new Set<string>() };
      seen.where.add(`${capture.state} ${capture.width} ${capture.theme}`);
      merged.set(key, seen);
    }
  }
  if (merged.size === 0) {
    return "<p>Nothing the checks can see.</p>";
  }
  const sorted = [...merged.values()].sort(
    (a, b) => a.severity.localeCompare(b.severity) || a.rule.localeCompare(b.rule),
  );
  return `<details><summary>${sorted.length} problem${sorted.length === 1 ? "" : "s"} the checks found</summary><ul class="problems">${sorted
    .map(
      (problem) =>
        `<li><span class="${problem.severity.toLowerCase()}">${problem.severity}</span> <b>${escape(problem.rule)}</b>: ${escape(problem.detail)} <span class="path">(${[...problem.where].slice(0, 6).map(escape).join("; ")}${problem.where.size > 6 ? `; and ${problem.where.size - 6} more` : ""})</span></li>`,
    )
    .join("")}</ul></details>`;
}

function sitePage(site: string, captures: Capture[]): void {
  const screens = new Map<string, Capture[]>();
  for (const capture of captures) {
    screens.set(capture.screen, [...(screens.get(capture.screen) ?? []), capture]);
  }
  const widths = [...new Set(captures.map((capture) => capture.width))].sort((a, b) => b - a);
  const states = [...new Set(captures.map((capture) => capture.state))].sort(
    (a, b) => rank(a) - rank(b) || a.localeCompare(b),
  );
  const sections = [...screens.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([screen, shots]) => {
      const figures = shots
        .sort((a, b) => rank(a.state) - rank(b.state) || a.state.localeCompare(b.state) || b.width - a.width)
        .map(
          (shot) =>
            `<figure data-theme="${shot.theme}" data-width="${shot.width}" data-state="${escape(shot.state)}"><a href="${escape(shot.file)}"><img loading="lazy" src="${escape(shot.file)}" alt="${escape(`${screen}, ${shot.state}, ${shot.width} px, ${shot.theme}`)}"></a><figcaption>${escape(shot.state)} · ${shot.width} · ${shot.theme}${shot.problems.some((p) => p.severity === "P0") ? ' · <span class="p0">P0</span>' : ""}</figcaption></figure>`,
        )
        .join("");
      return `<section id="${escape(screen)}"><h2>${escape(screen)}</h2><div class="path">${escape(shots[0]!.title)}</div>${problemList(shots)}<div class="grid">${figures}</div></section>`;
    })
    .join("\n");
  writeFileSync(
    join(out, site, "index.html"),
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${SITE_NAMES[site]}: UI review ${today}</title><style>${STYLE}</style><header><h1><a href="../index.html">UI review</a> · ${SITE_NAMES[site]}</h1>${FILTERS(widths, states)}</header><main>${sections}</main>`,
  );
}

type Count = Record<"P0" | "P1" | "P2", number>;

/** The overall page: each site's counts, and the problems by rule across every site. */
function overallPage(captures: Capture[], status: number): void {
  const bySite: Record<string, Count & { screens: number; pictures: number }> = {};
  const byRule = new Map<string, Count & { sites: Set<string> }>();
  for (const site of SITES) {
    const own = captures.filter((capture) => capture.site === site);
    bySite[site] = {
      P0: 0,
      P1: 0,
      P2: 0,
      screens: new Set(own.map((c) => c.screen)).size,
      pictures: own.length,
    };
  }
  for (const capture of captures) {
    for (const problem of capture.problems) {
      bySite[capture.site]![problem.severity] += 1;
      const rule = byRule.get(problem.rule) ?? { P0: 0, P1: 0, P2: 0, sites: new Set<string>() };
      rule[problem.severity] += 1;
      rule.sites.add(capture.site);
      byRule.set(problem.rule, rule);
    }
  }
  const siteRows = SITES.map(
    (site) =>
      `<tr><td><a href="${site}/index.html">${SITE_NAMES[site]}</a></td><td class="n">${bySite[site]!.screens}</td><td class="n">${bySite[site]!.pictures}</td><td class="n p0">${bySite[site]!.P0}</td><td class="n p1">${bySite[site]!.P1}</td><td class="n p2">${bySite[site]!.P2}</td>${options.before ? `<td><a href="${site}/compare.html">before and after</a></td>` : ""}</tr>`,
  ).join("");
  const ruleRows = [...byRule.entries()]
    .sort(([, a], [, b]) => b.P0 - a.P0 || b.P1 - a.P1 || b.P2 - a.P2)
    .map(
      ([rule, count]) =>
        `<tr><td>${escape(rule)}</td><td class="n p0">${count.P0}</td><td class="n p1">${count.P1}</td><td class="n p2">${count.P2}</td><td>${[...count.sites].join(", ")}</td></tr>`,
    )
    .join("");
  writeFileSync(
    join(out, "index.html"),
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>UI review ${today}</title><style>${STYLE}</style><header><h1>UI review, ${today}</h1><span class="path">widths ${options.widths}; themes ${options.themes}; states ${escape(options.states)}${options["no-specs"] ? "" : " and every state the screens specs draw"}; the browser run ${status === 0 ? "passed" : `exited ${status}: see report.json`}</span></header><main>
<section><h2>The sites</h2><p>Counts are what the checks found on each picture (e2e/ui-audit.ts): one problem on one picture counts once, so a rule broken at five widths in two themes counts ten. The findings a person wrote are in private/ui-review/findings.md.</p><table><tr><th>Site</th><th>Screens</th><th>Pictures</th><th>P0</th><th>P1</th><th>P2</th>${options.before ? "<th></th>" : ""}</tr>${siteRows}</table></section>
<section><h2>By rule</h2><table><tr><th>Rule</th><th>P0</th><th>P1</th><th>P2</th><th>Sites</th></tr>${ruleRows}</table></section></main>`,
  );
  writeFileSync(join(out, "audit.json"), JSON.stringify({ bySite, captures }, null, 1));
}

/** Before and after, side by side: each picture of this run beside the same one in the earlier run. */
function comparePages(before: string, captures: Capture[]): void {
  for (const site of SITES) {
    const own = captures
      .filter((capture) => capture.site === site && existsSync(join(before, site, capture.file)))
      .sort((a, b) => a.screen.localeCompare(b.screen) || rank(a.state) - rank(b.state) || b.width - a.width);
    mkdirSync(join(out, site, "before"), { recursive: true });
    const rows = own
      .map((capture) => {
        cpSync(join(before, site, capture.file), join(out, site, "before", capture.file));
        const earlier = JSON.parse(
          readFileSync(join(before, site, capture.file.replace(/\.jpg$/, ".json")), "utf8"),
        ) as Capture;
        const tally = (shot: Capture) =>
          (["P0", "P1", "P2"] as const)
            .map((severity) => `${shot.problems.filter((p) => p.severity === severity).length} ${severity}`)
            .join(", ");
        const caption = `${capture.screen} · ${capture.state} · ${capture.width} · ${capture.theme}`;
        return `<figure data-theme="${capture.theme}" data-width="${capture.width}" data-state="${escape(capture.state)}"><figcaption>${escape(caption)}</figcaption><div class="pair"><a href="before/${escape(capture.file)}"><img loading="lazy" src="before/${escape(capture.file)}" alt="Before: ${escape(caption)}"></a><a href="${escape(capture.file)}"><img loading="lazy" src="${escape(capture.file)}" alt="After: ${escape(caption)}"></a></div><figcaption>Before: ${tally(earlier)}. After: ${tally(capture)}.</figcaption></figure>`;
      })
      .join("\n");
    const widths = [...new Set(own.map((capture) => capture.width))].sort((a, b) => b - a);
    const states = [...new Set(own.map((capture) => capture.state))].sort((a, b) => rank(a) - rank(b));
    writeFileSync(
      join(out, site, "compare.html"),
      `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${SITE_NAMES[site]}: before and after</title><style>${STYLE} figure img { height: 420px; }</style><header><h1><a href="../index.html">UI review</a> · ${SITE_NAMES[site]}: before (${escape(relative(out, before))}) and after</h1>${FILTERS(widths, states)}</header><main><div class="grid" style="grid-template-columns: 1fr">${rows}</div></main>`,
    );
  }
}

let status = 0;
if (!options["index-only"]) {
  const servers = await serveSites();
  status = runBrowser();
  for (const server of servers) {
    server.kill();
  }
}
const captures = collect();
for (const site of SITES) {
  sitePage(
    site,
    captures.filter((capture) => capture.site === site),
  );
}
overallPage(captures, status);
if (options.before) {
  comparePages(resolve(options.before), captures);
}
console.log(
  `ui-review: ${captures.length} pictures in ${out}/index.html${status === 0 ? "" : ` (the browser run exited ${status})`}`,
);
