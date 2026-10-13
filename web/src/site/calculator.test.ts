import { describe, expect, it } from "vitest";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import { DEFAULT_OCCUPANCY, earnings, OCCUPANCY_CHOICES, priceToPerMinute } from "./calculator";

const base = { priceRupeesPerHour: "9.00", hoursFreePerDay: 8, occupancyPercent: 20 };

describe("the earnings calculator", () => {
  it("is hours free × price × share rented × what the host keeps, in whole paise", () => {
    // ₹9.00 an hour is 15 paise a minute. 8 hours at 20% is 1.6 rented hours: 1.6 × ₹9.00 = ₹14.40 paid.
    // The host keeps 80% of that: ₹11.52 a day, and ₹345.60 over 30 days.
    expect(earnings(base)).toEqual({
      pricePaisePerMin: 15,
      paidByRentersPerDay: 1_440,
      hostPerDay: 1_152,
      feePerDay: 288,
      hostPerMonth: 34_560,
    });
  });

  it("keeps what the platform's commission leaves, and the two always add up to what renters paid", () => {
    expect(COMMISSION_PERCENT).toBe(20);
    for (const price of ["0.60", "7.20", "10.80", "13.20", "42.00"]) {
      for (const hours of [1, 5, 13, 24]) {
        for (const occupancy of OCCUPANCY_CHOICES) {
          const result = earnings({
            priceRupeesPerHour: price,
            hoursFreePerDay: hours,
            occupancyPercent: occupancy,
          })!;
          expect(result.hostPerDay + result.feePerDay).toBe(result.paidByRentersPerDay);
          expect(result.hostPerDay).toBe(Math.floor((result.paidByRentersPerDay * 80) / 100));
          expect(Number.isInteger(result.hostPerMonth)).toBe(true);
        }
      }
    }
  });

  it("starts at 20% occupancy, which is one of the choices", () => {
    expect(DEFAULT_OCCUPANCY).toBe(20);
    expect(OCCUPANCY_CHOICES).toContain(DEFAULT_OCCUPANCY);
  });

  it("earns more when more of the free hours are rented", () => {
    const low = earnings({ ...base, occupancyPercent: 10 })!;
    const high = earnings({ ...base, occupancyPercent: 50 })!;
    expect(high.hostPerDay).toBeGreaterThan(low.hostPerDay);
    expect(high.hostPerDay).toBe(2_880);
  });

  it("rounds a price to a whole paisa a minute, and keeps it within what a host may ask", () => {
    expect(priceToPerMinute("9.00")).toBe(15);
    expect(priceToPerMinute("9.29")).toBe(15);
    expect(priceToPerMinute("9.31")).toBe(16);
    expect(priceToPerMinute("0")).toBe(1);
    expect(priceToPerMinute("5000")).toBe(1_000);
  });

  it("has no answer while an input isn't a usable number", () => {
    expect(earnings({ ...base, priceRupeesPerHour: "" })).toBeNull();
    expect(earnings({ ...base, priceRupeesPerHour: "nine" })).toBeNull();
    expect(earnings({ ...base, priceRupeesPerHour: "9.999" })).toBeNull();
    expect(earnings({ ...base, hoursFreePerDay: 0 })).toBeNull();
    expect(earnings({ ...base, hoursFreePerDay: 25 })).toBeNull();
    expect(earnings({ ...base, hoursFreePerDay: 2.5 })).toBeNull();
    expect(earnings({ ...base, occupancyPercent: 0 })).toBeNull();
    expect(earnings({ ...base, occupancyPercent: 101 })).toBeNull();
  });
});
