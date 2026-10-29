import { existsSync, readFileSync } from "node:fs";

/**
 * What Chrome's own throttling measured on each screen in the last green run on main (ADR 0018, "Measuring the budgets"):
 * the median of the runs, in ms, by screen. CI keeps it as an artifact and hands the next run the file; a run
 * that gets more than REGRESSION_LIMIT slower than it fails.
 */
export type Baseline = {
  devtools: Record<string, number>;
  firstVisit: Record<string, number>;
};

/** How much slower than the baseline a screen may get: 10%. */
export const REGRESSION_LIMIT = 0.1;

/** The baseline at `path`, or null when there is none yet (the first run, or after the artifact expired). A file that doesn't parse is an error, never a pass. */
export function readBaseline(path: string): Baseline | null {
  if (!existsSync(path)) {
    return null;
  }
  const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
  if (!isBaseline(parsed)) {
    throw new Error(`${path} is not a Lighthouse baseline (devtools and firstVisit: screen name to ms)`);
  }
  return parsed;
}

function isBaseline(value: unknown): value is Baseline {
  const isMillis = (record: unknown) =>
    typeof record === "object" &&
    record !== null &&
    Object.values(record).every((ms) => typeof ms === "number" && Number.isFinite(ms) && ms > 0);
  return (
    typeof value === "object" &&
    value !== null &&
    isMillis((value as Baseline).devtools) &&
    isMillis((value as Baseline).firstVisit)
  );
}

/** The screens that are more than `limit` slower than the baseline. A screen the baseline doesn't have is new, and has nothing to be slower than. */
export function regressions(
  measured: Record<string, number>,
  baseline: Record<string, number>,
  limit = REGRESSION_LIMIT,
): string[] {
  return Object.entries(measured).flatMap(([screen, ms]) => {
    const before = baseline[screen];
    if (before === undefined || ms <= before * (1 + limit)) {
      return [];
    }
    return [
      `${screen}: ${ms} ms, ${Math.round((ms / before - 1) * 100)}% slower than the ${before} ms of the last green run on main (limit ${Math.round(limit * 100)}%)`,
    ];
  });
}
