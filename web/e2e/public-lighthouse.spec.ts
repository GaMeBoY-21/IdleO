import { chromium, test } from "@playwright/test";
import lighthouse from "lighthouse";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/**
 * The public pages' speed budgets (ADR 0029): each page, signed out, on Lighthouse's mobile profile, three runs from a
 * cold cache; `lhci assert` holds the median to the stricter budgets for public pages in lighthouserc.json (LCP 1.5 s,
 * TBT 100 ms, CLS 0, accessibility 100). The first-load size, 80 KB, is `pnpm size` (scripts/site-size.ts).
 */
const PORT = 9334;
const WEB = process.env.SITE_URL ?? "http://localhost:3002";
const RUNS = 3;
// The front door is measured as "/?site", which the website serves as "/": the query tells `lhci assert` (lighthouserc.json)
// that it is a public page and not the app's Home.
const PAGES = [
  "/?site",
  "/pricing/",
  "/gpus/",
  "/earn/",
  "/docs/",
  "/docs/cli/",
  "/docs/api/",
  "/docs/api/schemas/",
  "/terms/",
];

const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) / 2)]!;

test("the public pages hold their speed and accessibility budgets", async () => {
  const context = await chromium.launchPersistentContext(
    mkdtempSync(join(tmpdir(), "idleo-public-lighthouse-")),
    {
      args: [`--remote-debugging-port=${PORT}`],
      viewport: { width: 412, height: 823 },
    },
  );
  try {
    await context.pages()[0]?.goto("about:blank");
    const summary: Record<string, Record<string, number | number[]>> = {};
    for (const path of PAGES) {
      const runs: { lcp: number; tbt: number; cls: number; accessibility: number; performance: number }[] =
        [];
      for (let run = 1; run <= RUNS; run++) {
        const result = await lighthouse(`${WEB}${path}`, {
          port: PORT,
          output: ["json", "html"],
          logLevel: "error",
          onlyCategories: ["performance", "accessibility", "best-practices", "seo"],
        });
        if (!result) {
          throw new Error(`Lighthouse returned nothing for ${path}`);
        }
        const [json, html] = result.report as string[];
        mkdirSync(".lighthouseci/reports", { recursive: true });
        writeFileSync(`.lighthouseci/lhr-${Date.now()}.json`, json!);
        writeFileSync(`.lighthouseci/reports/public${path.replaceAll("/", "-")}${run}.html`, html!);
        const audits = result.lhr.audits;
        runs.push({
          lcp: Math.round(audits["largest-contentful-paint"]?.numericValue ?? -1),
          tbt: Math.round(audits["total-blocking-time"]?.numericValue ?? -1),
          cls: audits["cumulative-layout-shift"]?.numericValue ?? -1,
          accessibility: Math.round((result.lhr.categories.accessibility?.score ?? 0) * 100),
          performance: Math.round((result.lhr.categories.performance?.score ?? 0) * 100),
        });
      }
      summary[path] = {
        lcpMs: median(runs.map((r) => r.lcp)),
        tbtMs: median(runs.map((r) => r.tbt)),
        cls: median(runs.map((r) => r.cls)),
        accessibility: median(runs.map((r) => r.accessibility)),
        performance: median(runs.map((r) => r.performance)),
        lcpRunsMs: runs.map((r) => r.lcp),
      };
    }
    mkdirSync(".lighthouseci", { recursive: true });
    writeFileSync(".lighthouseci/public-summary.json", `${JSON.stringify(summary, null, 2)}\n`);
    console.table(summary);
  } finally {
    await context.close();
  }
});
