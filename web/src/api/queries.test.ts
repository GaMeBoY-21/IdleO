import { describe, expect, it } from "vitest";
import { isChanging } from "./queries";

describe("which sessions are watched", () => {
  const settled = { state: "COMPLETED", reservedPaise: 0, accruedPaise: 0 };

  it("watches a live session", () => {
    expect(isChanging({ ...settled, state: "RUNNING" })).toBe(true);
  });

  it("keeps watching a finished session until its charge settles and its hold is released", () => {
    expect(isChanging({ ...settled, accruedPaise: 2, reservedPaise: 60 })).toBe(true);
    expect(isChanging({ ...settled, reservedPaise: 58 })).toBe(true);
    expect(isChanging({ ...settled, state: "FAILED", accruedPaise: 2 })).toBe(true);
  });

  it("stops once nothing about it can change", () => {
    expect(isChanging(settled)).toBe(false);
    expect(isChanging({ ...settled, state: "FAILED" })).toBe(false);
  });
});
