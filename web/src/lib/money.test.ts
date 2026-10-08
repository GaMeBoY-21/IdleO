// @vitest-environment node
import { describe, expect, it } from "vitest";
import { formatEstimate, formatPaise, formatPaiseRange } from "./money";

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

describe("rupees typed by the renter", () => {
  it("are read as text, exactly, into paise", async () => {
    const { parseRupees } = await import("./money");
    expect(parseRupees("500")).toBe(50_000);
    expect(parseRupees("7.2")).toBe(720);
    expect(parseRupees(" 0.07 ")).toBe(7);
    expect(parseRupees("1,000")).toBeNull();
    expect(parseRupees("1.005")).toBeNull();
    expect(parseRupees("-1")).toBeNull();
    expect(parseRupees("")).toBeNull();
  });
});

describe("₹ an hour and paise a minute", () => {
  it("round-trip exactly for every price", async () => {
    const { paisePerMinute, rupeesPerHour } = await import("./money");
    for (let paise = 1; paise <= 20_000; paise++) {
      expect(paisePerMinute(rupeesPerHour(paise))).toBe(paise);
    }
  });

  it("show ₹0.60 an hour as 1 paisa a minute, and snap a price between steps down", async () => {
    const { paisePerMinute, rupeesPerHour } = await import("./money");
    expect(rupeesPerHour(1)).toBe("0.60");
    expect(rupeesPerHour(12)).toBe("7.20");
    expect(paisePerMinute("7.20")).toBe(12);
    expect(paisePerMinute("7.79")).toBe(12);
    expect(paisePerMinute("7.80")).toBe(13);
    expect(paisePerMinute("0.59")).toBe(0);
    expect(paisePerMinute("seven")).toBeNull();
  });
});

describe("formatPaiseRange", () => {
  it("writes the rupee sign once, and one figure when the ends meet", () => {
    expect(formatPaiseRange(720, 1_080)).toBe("₹7.20–10.80");
    expect(formatPaiseRange(720, 720)).toBe("₹7.20");
    expect(formatPaiseRange(99_000, 1_20_000)).toBe("₹990.00–1,200.00");
  });
});

describe("formatEstimate", () => {
  it("rounds each end to the nearest whole rupee", () => {
    // 45 minutes at 12 to 18 paise a minute: ₹5.40 to ₹8.10.
    expect(formatEstimate(540, 810)).toBe("≈ ₹5–8");
    expect(formatEstimate(450, 849)).toBe("≈ ₹5–8");
    expect(formatEstimate(1_23_450, 2_00_000)).toBe("≈ ₹1,235–2,000");
  });

  it("gives one figure when both ends round alike, and says when it's under a rupee", () => {
    expect(formatEstimate(540, 549)).toBe("≈ ₹5");
    expect(formatEstimate(0, 49)).toBe("under ₹1");
    expect(formatEstimate(10, 150)).toBe("≈ ₹1–2");
  });

  it("refuses what isn't whole paise, low to high", () => {
    expect(() => formatEstimate(5.5, 10)).toThrow(RangeError);
    expect(() => formatEstimate(10, 5)).toThrow(RangeError);
  });
});
