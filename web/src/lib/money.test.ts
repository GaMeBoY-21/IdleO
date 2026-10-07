// @vitest-environment node
import { describe, expect, it } from "vitest";
import { formatPaise } from "./money";

describe("formatPaise", () => {
  it("formats zero and small amounts with two paise digits", () => {
    expect(formatPaise(0)).toBe("₹0.00");
    expect(formatPaise(1)).toBe("₹0.01");
    expect(formatPaise(6)).toBe("₹0.06");
    expect(formatPaise(99)).toBe("₹0.99");
    expect(formatPaise(100)).toBe("₹1.00");
  });

  it("groups thousands, lakhs and crores the Indian way", () => {
    expect(formatPaise(49_994)).toBe("₹499.94");
    expect(formatPaise(50_000)).toBe("₹500.00");
    expect(formatPaise(123_456)).toBe("₹1,234.56");
    expect(formatPaise(12_345_678)).toBe("₹1,23,456.78");
    expect(formatPaise(100_000_000)).toBe("₹10,00,000.00");
    expect(formatPaise(1_000_000_000)).toBe("₹1,00,00,000.00");
    expect(formatPaise(123_456_789_012)).toBe("₹1,23,45,67,890.12");
  });

  it("shows a negative amount with a true minus sign", () => {
    expect(formatPaise(-250)).toBe("−₹2.50");
    expect(formatPaise(-12_345_678)).toBe("−₹1,23,456.78");
  });

  it("takes bigints beyond the safe integer range exactly", () => {
    expect(formatPaise(9_007_199_254_740_993n)).toBe("₹9,00,71,99,25,47,409.93");
  });

  it("refuses anything that isn't a whole number of paise", () => {
    expect(() => formatPaise(1.5)).toThrow(RangeError);
    expect(() => formatPaise(0.1 + 0.2)).toThrow(RangeError);
    expect(() => formatPaise(Number.NaN)).toThrow(RangeError);
    expect(() => formatPaise(Number.POSITIVE_INFINITY)).toThrow(RangeError);
    expect(() => formatPaise(2 ** 53)).toThrow(RangeError);
  });
});
