/**
 * The one way the app shows money (web/DESIGN.md, principle 2): integer paise to rupees with Indian digit
 * grouping, ₹1,23,456.78. It never goes through floating point; it refuses anything that isn't a whole number
 * of paise, rather than showing a wrong figure.
 */
export function formatPaise(paise: number | bigint): string {
  let value: bigint;
  if (typeof paise === "bigint") {
    value = paise;
  } else {
    if (!Number.isSafeInteger(paise)) {
      throw new RangeError(`Money must be a whole number of paise, not ${paise}`);
    }
    value = BigInt(paise);
  }
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(3, "0");
  const rupees = digits.slice(0, -2);
  const paiseDigits = digits.slice(-2);
  return `${negative ? "−" : ""}₹${groupIndian(rupees)}.${paiseDigits}`;
}

/** 1234567 → 12,34,567: the last three digits, then pairs. */
function groupIndian(rupees: string): string {
  if (rupees.length <= 3) {
    return rupees;
  }
  const lastThree = rupees.slice(-3);
  let rest = rupees.slice(0, -3);
  const pairs: string[] = [];
  while (rest.length > 2) {
    pairs.unshift(rest.slice(-2));
    rest = rest.slice(0, -2);
  }
  if (rest.length > 0) {
    pairs.unshift(rest);
  }
  return `${pairs.join(",")},${lastThree}`;
}

/**
 * Rupees typed by the renter, "7.20" or "500", as integer paise; null when it isn't a plain amount with at most
 * two decimal places. Parsed as text, never through floating point.
 */
export function parseRupees(text: string): number | null {
  const match = /^\s*(\d{1,9})(?:\.(\d{1,2}))?\s*$/.exec(text);
  if (!match) {
    return null;
  }
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

/** Paise as the plain rupee text an input holds, "7.20": no ₹ and no grouping, for editing (formatPaise shows). */
export function rupeeInput(paise: number): string {
  if (!Number.isSafeInteger(paise) || paise < 0) {
    throw new RangeError(`Money must be a whole, non-negative number of paise, not ${paise}`);
  }
  return `${Math.floor(paise / 100)}.${String(paise % 100).padStart(2, "0")}`;
}

/**
 * The renter thinks in rupees an hour; the API takes paise a minute. ₹0.60 an hour is exactly 1 paisa a minute, so
 * a price is a whole number of ₹0.60 steps: one typed between steps snaps down to the step below, so the booking
 * never allows more than the renter typed. Null when the text isn't an amount.
 */
export function paisePerMinute(rupeesPerHour: string): number | null {
  const paisePerHour = parseRupees(rupeesPerHour);
  return paisePerHour === null ? null : Math.floor(paisePerHour / 60);
}

/** A price in paise a minute, as the rupees-an-hour text the price input holds: 12 → "7.20". */
export const rupeesPerHour = (paisePerMin: number) => rupeeInput(paisePerMin * 60);

/** One step of the price input, ₹0.60 an hour. */
export const PRICE_STEP = "0.60";

/** A range of money, "₹7.20–10.80", the rupee sign once; one figure when both ends are the same. */
export function formatPaiseRange(min: number, max: number): string {
  return min === max ? formatPaise(min) : `${formatPaise(min)}–${formatPaise(max).slice("₹".length)}`;
}

/**
 * An estimate, the one place money is rounded: "≈ ₹5–8", whole rupees, each end to the nearest (half up), in integer
 * arithmetic; "≈ ₹5" when both ends round alike; "under ₹1" when even the top is less than a rupee. Only for what a
 * session might cost before it runs (web/DESIGN.md "Book"), never for a charge, a hold or credit.
 */
export function formatEstimate(minPaise: number, maxPaise: number): string {
  if (
    !Number.isSafeInteger(minPaise) ||
    !Number.isSafeInteger(maxPaise) ||
    minPaise < 0 ||
    maxPaise < minPaise
  ) {
    throw new RangeError(`An estimate needs whole paise, low to high, not ${minPaise} to ${maxPaise}`);
  }
  const rupees = (paise: number) => Math.floor((paise + 50) / 100);
  const [low, high] = [rupees(minPaise), rupees(maxPaise)];
  if (high === 0) {
    return "under ₹1";
  }
  const whole = (value: number) => formatPaise(value * 100).slice(0, -".00".length);
  return low === high ? `≈ ${whole(high)}` : `≈ ${whole(Math.max(low, 1))}–${whole(high).slice("₹".length)}`;
}
