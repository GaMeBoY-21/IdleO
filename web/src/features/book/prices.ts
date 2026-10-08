import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";

type Availability = components["schemas"]["Availability"];

/** The most per hour to start from when no host is online to price it: ₹30.00 an hour. */
const FALLBACK_PRICE_PAISE_PER_MIN = 50;

/** GPU memory as renters read it: "12 GB", from the MB the API counts. */
export const gigabytes = (mb: number) => `${Math.round(mb / 1024)} GB`;

/** The GPU memory a booking may ask for: the template's least, or more, as availability shows. */
export function memoryChoices(template: Template, availability: Availability | undefined): number[] {
  const sizes = new Set([template.minVramMb]);
  for (const gpu of availability?.gpus ?? []) {
    if (gpu.vramMb > template.minVramMb) {
      sizes.add(gpu.vramMb);
    }
  }
  return [...sizes].sort((a, b) => a - b);
}

/**
 * The most per hour to start with: the highest price among hosts with enough memory that are free now, so the
 * booking can match; failing that, among those online; failing that, ₹30.00 an hour.
 */
export function defaultPrice(availability: Availability | undefined, vramMb: number): number {
  const fit = (availability?.gpus ?? []).filter((gpu) => gpu.vramMb >= vramMb);
  const free = fit.filter((gpu) => gpu.hostsIdle > 0);
  const pool = free.length > 0 ? free : fit;
  return pool.length > 0
    ? Math.max(...pool.map((gpu) => gpu.pricePaisePerMin.max))
    : FALLBACK_PRICE_PAISE_PER_MIN;
}

/** The lowest price among hosts free now with enough memory, for a template's card; null when none is free. */
export function priceFrom(availability: Availability | undefined, vramMb: number): number | null {
  const free = (availability?.gpus ?? []).filter((gpu) => gpu.vramMb >= vramMb && gpu.hostsIdle > 0);
  return free.length > 0 ? Math.min(...free.map((gpu) => gpu.pricePaisePerMin.min)) : null;
}
