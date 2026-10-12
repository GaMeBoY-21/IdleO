import { describe, expect, it } from "vitest";
import { busiestMinute } from "../../e2e/api-rate";

describe("the busiest minute of requests", () => {
  it("is none for none, and one for one", () => {
    expect(busiestMinute([])).toBe(0);
    expect(busiestMinute([5])).toBe(1);
  });

  it("counts what falls in any 60 seconds, not in a clock minute", () => {
    const burst = Array.from({ length: 10 }, (_, i) => 55_000 + i * 1_000); // 10 across a clock-minute boundary
    expect(busiestMinute([0, ...burst, 200_000])).toBe(10);
  });

  it("lets a request 60 seconds later start a new window", () => {
    expect(busiestMinute([0, 30_000, 59_999])).toBe(3);
    expect(busiestMinute([0, 30_000, 60_000])).toBe(2);
  });

  it("finds the busiest of several bursts", () => {
    const quiet = [0, 10_000, 20_000];
    const busy = Array.from({ length: 7 }, (_, i) => 300_000 + i * 100);
    expect(busiestMinute([...quiet, ...busy])).toBe(7);
  });
});
