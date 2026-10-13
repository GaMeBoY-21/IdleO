import { formatPaise } from "@/lib/money";

/** One GPU memory class, as GET /v1/public/availability gives it (ADR 0029). Prices are paise an hour. */
export interface HourlyRange {
  min: number;
  max: number;
}

export interface ClassRow {
  gpuMemoryGb: number;
  online: boolean;
  /** Only with at least three hosts online: one host's price is that host's. */
  livePricePaisePerHour: HourlyRange | null;
  typicalPricePaisePerHour: HourlyRange;
}

export interface PublicAvailability {
  asOf: string;
  classes: ClassRow[];
  startingCreditPaise: number | null;
}

/** The range to show for a class, and whether it's live or only typical. */
export function shown(row: ClassRow): { range: HourlyRange; kind: "live" | "typical" } {
  return row.livePricePaisePerHour
    ? { range: row.livePricePaisePerHour, kind: "live" }
    : { range: row.typicalPricePaisePerHour, kind: "typical" };
}

/** "₹7.20–10.80 an hour", or "₹7.20 an hour" when the two ends are the same. */
export function formatRange(range: HourlyRange, suffix = " an hour"): string {
  const low = formatPaise(range.min);
  return range.min === range.max ? `${low}${suffix}` : `${low}–${formatPaise(range.max).slice(1)}${suffix}`;
}

export interface WorkedExample {
  /** What it is, in words, from the catalog. */
  label: string;
  /** How long it typically runs, in minutes, from the catalog. */
  minutes: number;
  gpuMemoryGb: number;
}

export interface Priced extends WorkedExample {
  lowPaise: number;
  highPaise: number;
  kind: "live" | "typical";
}

/**
 * What a typical run costs at the class's prices: its minutes at the lowest and the highest price a minute. A GPU is
 * charged by the minute, so an hourly price is whole paise a minute times sixty.
 */
export function priceExample(example: WorkedExample, rows: ClassRow[]): Priced | null {
  const row = rows.find((candidate) => candidate.gpuMemoryGb === example.gpuMemoryGb);
  if (!row) {
    return null;
  }
  const { range, kind } = shown(row);
  return {
    ...example,
    lowPaise: Math.round(range.min / 60) * example.minutes,
    highPaise: Math.round(range.max / 60) * example.minutes,
    kind,
  };
}
