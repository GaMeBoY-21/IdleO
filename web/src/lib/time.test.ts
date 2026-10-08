// @vitest-environment node
import { describe, expect, it } from "vitest";
import { formatDuration, formatElapsed, formatRelative } from "./time";

const NOW = Date.parse("2026-10-07T06:34:00Z");
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe("relative times", () => {
  it("say how long ago, in whole units rounded down", () => {
    expect(formatRelative(ago(20_000), NOW)).toBe("just now");
    expect(formatRelative(ago(60_000), NOW)).toBe("1 min ago");
    expect(formatRelative(ago(18 * 60_000 + 59_000), NOW)).toBe("18 min ago");
    expect(formatRelative(ago(2 * 3_600_000 + 59 * 60_000), NOW)).toBe("2 h ago");
    expect(formatRelative(ago(30 * 3_600_000), NOW)).toBe("yesterday");
    expect(formatRelative(ago(3 * 86_400_000), NOW)).toBe("3 days ago");
  });

  it("give the date once it's more than a week ago", () => {
    expect(formatRelative("2026-09-20T06:00:00Z", NOW)).toMatch(/^20 Sept?$/);
  });

  it("say how long until a time to come", () => {
    expect(formatRelative(new Date(NOW + 15 * 60_000).toISOString(), NOW)).toBe("in 15 min");
    expect(formatRelative(new Date(NOW + 10_000).toISOString(), NOW)).toBe("in under a minute");
  });
});

describe("elapsed time", () => {
  it("ticks as a clock, minutes then hours", () => {
    expect(formatElapsed(ago(0), NOW)).toBe("0:00");
    expect(formatElapsed(ago(18 * 60_000 + 4_000), NOW)).toBe("18:04");
    expect(formatElapsed(ago(3_725_000), NOW)).toBe("1:02:05");
  });

  it("is never negative, whatever the clocks say", () => {
    expect(formatElapsed(new Date(NOW + 5_000).toISOString(), NOW)).toBe("0:00");
  });
});

describe("durations", () => {
  it("keep seconds only while they matter", () => {
    expect(formatDuration(ago(22_000), NOW)).toBe("22 s");
    expect(formatDuration(ago(6 * 60_000 + 10_000), NOW)).toBe("6 min 10 s");
    expect(formatDuration(ago(41 * 60_000 + 5_000), NOW)).toBe("41 min");
    expect(formatDuration(ago(65 * 60_000), NOW)).toBe("1 h 05 min");
  });
});
