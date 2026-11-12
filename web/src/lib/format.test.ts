import { describe, expect, it } from "vitest";
import { count, percent } from "./format";

describe("formats", () => {
  it("names one and many, grouped the Indian way", () => {
    expect(count(1, "day")).toBe("1 day");
    expect(count(2, "day")).toBe("2 days");
    expect(count(0, "file")).toBe("0 files");
    expect(count(124000, "file")).toBe("1,24,000 files");
    expect(count(3, "person", "people")).toBe("3 people");
  });
  it("rounds a percentage to a whole one", () => {
    expect(percent(41.6)).toBe("42%");
  });
});
