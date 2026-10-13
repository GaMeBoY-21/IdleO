import { COMMISSION_PERCENT, MAX_PRICE, MIN_PRICE, youKeep } from "@/lib/hosting";
import { parseRupees } from "@/lib/money";

/**
 * The earnings calculator on the host page (ADR 0029), all in integer paise: hours free a day × your price × what
 * share of those hours are rented × what the host keeps after the platform's fee. It promises nothing: the share
 * rented depends on demand, so it's the person's to choose, and it starts low.
 */

/** What share of the free hours is rented, to start with. Early on it may well be lower. */
export const DEFAULT_OCCUPANCY = 20;
/** The shares to choose from, in percent. */
export const OCCUPANCY_CHOICES = [5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100] as const;
/** A month, for the monthly figure, is 30 days. */
export const DAYS_IN_A_MONTH = 30;

export interface Earnings {
  /** The price as a host sets it: whole paise a minute, so ₹0.60 an hour at a time. */
  pricePaisePerMin: number;
  /** What renters pay on a day, before the platform's fee. */
  paidByRentersPerDay: number;
  /** What the host keeps on a day. */
  hostPerDay: number;
  /** What the platform keeps on a day. */
  feePerDay: number;
  /** A day's earnings over a 30-day month. */
  hostPerMonth: number;
}

export interface Inputs {
  /** What the person typed, in rupees an hour ("9", "9.60"). */
  priceRupeesPerHour: string;
  /** Hours the machine is free to rent in a day, 1 to 24. */
  hoursFreePerDay: number;
  /** Percent of those hours that are rented, 1 to 100. */
  occupancyPercent: number;
}

/** The price a person typed as the whole paise a minute a host may ask; null if it isn't an amount. */
export function priceToPerMinute(rupeesPerHour: string): number | null {
  const paisePerHour = parseRupees(rupeesPerHour);
  if (paisePerHour === null) {
    return null;
  }
  return Math.min(MAX_PRICE, Math.max(MIN_PRICE, Math.round(paisePerHour / 60)));
}

/** The calculator's answer, or null while an input isn't a usable number. */
export function earnings(inputs: Inputs): Earnings | null {
  const pricePaisePerMin = priceToPerMinute(inputs.priceRupeesPerHour);
  const { hoursFreePerDay: hours, occupancyPercent: occupancy } = inputs;
  if (
    pricePaisePerMin === null ||
    !Number.isInteger(hours) ||
    hours < 1 ||
    hours > 24 ||
    !Number.isInteger(occupancy) ||
    occupancy < 1 ||
    occupancy > 100
  ) {
    return null;
  }
  const paidByRentersPerDay = Math.floor((youKeep(pricePaisePerMin).perHour * hours * occupancy) / 100);
  const hostPerDay = Math.floor((paidByRentersPerDay * (100 - COMMISSION_PERCENT)) / 100);
  return {
    pricePaisePerMin,
    paidByRentersPerDay,
    hostPerDay,
    feePerDay: paidByRentersPerDay - hostPerDay,
    hostPerMonth: hostPerDay * DAYS_IN_A_MONTH,
  };
}
