/**
 * Counts, units and percentages, one way everywhere (web/DESIGN.md "Formats"): a count with its noun, singular or plural,
 * grouped the Indian way ("1 day", "2 days", "1,24,000 files"); a percentage as a whole number with no space ("42%").
 * Money is `formatPaise` (money.ts); times are time.ts.
 */
export function count(n: number, singular: string, plural = `${singular}s`): string {
  return `${n.toLocaleString("en-IN")} ${n === 1 ? singular : plural}`;
}

export function percent(n: number): string {
  return `${Math.round(n)}%`;
}
