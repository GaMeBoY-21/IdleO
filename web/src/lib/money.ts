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
