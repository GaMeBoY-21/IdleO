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
