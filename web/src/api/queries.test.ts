import { describe, expect, it } from "vitest";
import { isChanging } from "./queries";

describe("which sessions are watched", () => {
  it("watches a session until the API says it's settled", () => {
    expect(isChanging({ settled: false })).toBe(true);
    expect(isChanging({ settled: true })).toBe(false);
  });
});
