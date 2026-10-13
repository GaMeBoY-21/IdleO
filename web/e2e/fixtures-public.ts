/**
 * What the public availability answers (ADR 0029) in the public pages' tests: the same shape as the API's
 * (`GET /v1/public/availability`), with a typical range for every class and a live one only where three hosts are
 * online, as the server decides. The figures are the ones price-band-defaults and a few invented hosts give.
 */
export interface Range {
  min: number;
  max: number;
}
export interface ClassAnswer {
  gpuMemoryGb: number;
  online: boolean;
  livePricePaisePerHour: Range | null;
  typicalPricePaisePerHour: Range;
}

/** Paise an hour from paise a minute: a price a minute is whole paise, so an hour's is a multiple of 60. */
const hour = (paisePerMin: number) => paisePerMin * 60;

const typical = (low: number, high: number): Range => ({ min: hour(low), max: hour(high) });

export const classes = (): ClassAnswer[] => [
  { gpuMemoryGb: 8, online: true, livePricePaisePerHour: null, typicalPricePaisePerHour: typical(8, 14) },
  {
    gpuMemoryGb: 12,
    online: true,
    // Three hosts, at 12, 15 and 18 paise a minute.
    livePricePaisePerHour: { min: hour(12), max: hour(18) },
    typicalPricePaisePerHour: typical(12, 20),
  },
  { gpuMemoryGb: 16, online: false, livePricePaisePerHour: null, typicalPricePaisePerHour: typical(16, 28) },
  { gpuMemoryGb: 24, online: false, livePricePaisePerHour: null, typicalPricePaisePerHour: typical(24, 42) },
];

export const availability = (startingCreditPaise: number | null = null, asOf = "2026-10-13T10:00:00Z") => ({
  asOf,
  startingCreditPaise,
  classes: classes(),
});

/** Nobody online: every class offline, with its typical range. */
export const nobodyOnline = () => ({
  asOf: "2026-10-13T10:00:00Z",
  startingCreditPaise: null,
  classes: classes().map((row) => ({ ...row, online: false, livePricePaisePerHour: null })),
});
