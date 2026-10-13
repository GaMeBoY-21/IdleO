import { describe, expect, it } from "vitest";
import { formatRange, priceExample, shown, type ClassRow } from "./pricing";

const row = (over: Partial<ClassRow> = {}): ClassRow => ({
  gpuMemoryGb: 12,
  online: true,
  livePricePaisePerHour: { min: 720, max: 1_080 },
  typicalPricePaisePerHour: { min: 720, max: 1_200 },
  ...over,
});

describe("the pricing page's figures", () => {
  it("shows the live range when there is one, and otherwise the typical one, said to be typical", () => {
    expect(shown(row())).toEqual({ range: { min: 720, max: 1_080 }, kind: "live" });
    expect(shown(row({ livePricePaisePerHour: null }))).toEqual({
      range: { min: 720, max: 1_200 },
      kind: "typical",
    });
  });

  it("writes a range in rupees an hour from whole paise", () => {
    expect(formatRange({ min: 720, max: 1_080 })).toBe("₹7.20–10.80 an hour");
    expect(formatRange({ min: 720, max: 720 })).toBe("₹7.20 an hour");
    expect(formatRange({ min: 720, max: 1_080 }, "/h")).toBe("₹7.20–10.80/h");
  });

  it("works a typical run out from the minutes in the catalog and the class's price a minute", () => {
    // 45 minutes at 12 to 18 paise a minute.
    const priced = priceExample({ label: "Fine-tune, Standard", minutes: 45, gpuMemoryGb: 12 }, [row()])!;
    expect(priced).toMatchObject({ lowPaise: 540, highPaise: 810, kind: "live" });
    expect(priced.lowPaise).toBe(45 * 12);
    expect(priced.highPaise).toBe(45 * 18);
  });

  it("marks an example worked from a typical range as typical", () => {
    const priced = priceExample({ label: "Fine-tune, Quick", minutes: 15, gpuMemoryGb: 12 }, [
      row({ livePricePaisePerHour: null }),
    ])!;
    expect(priced.kind).toBe("typical");
    expect(priced.highPaise).toBe(15 * 20);
  });

  it("has no price for a class it hasn't heard about", () => {
    expect(priceExample({ label: "x", minutes: 15, gpuMemoryGb: 24 }, [row()])).toBeNull();
  });
});
