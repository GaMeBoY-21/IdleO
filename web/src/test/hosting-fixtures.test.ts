import { describe, expect, it } from "vitest";
import * as data from "../../e2e/fixtures-hosting";
import * as trust from "../../e2e/fixtures-trust";

/** The hosting screens' invented data adds up (e2e/fixtures-hosting.ts), as a renter's does (fixtures.test.ts). */
describe("the owner's earnings", () => {
  it("are the sum of their entries, in all and for each machine", () => {
    const sum = data.earningsEntries.reduce((total, entry) => total + entry.amountPaise, 0);
    expect(data.earnings.totalPaise).toBe(sum);
    expect(data.earnings.machines.reduce((total, machine) => total + machine.earnedPaise, 0)).toBe(sum);
  });

  it("have a week that is the entries since Monday, and days that add up to it", () => {
    const week = data.earningsEntries
      .filter((entry) => entry.createdAt >= data.WEEK_STARTS_AT)
      .reduce((total, entry) => total + entry.amountPaise, 0);
    expect(data.earnings.weekPaise).toBe(week);
    // Wednesday: the week's days are the last three of the seven.
    expect(data.earnings.days.slice(4).reduce((total, day) => total + day.earnedPaise, 0)).toBe(week);
    expect(data.earnings.days).toHaveLength(7);
  });

  it("count a giveback as a negative entry, labelled, beside the session it came from", () => {
    const back = data.earningsEntries.filter((entry) => entry.amountPaise < 0);
    expect(back.map((entry) => entry.label)).toEqual(["Given back for lost time"]);
    expect(back[0]!.kind).toBe("lost_minutes_refunded");
  });

  it("keep a removed machine's earnings in the total", () => {
    expect(data.earnings.machines.find((machine) => machine.removed)?.name).toBe("Old laptop");
  });
});

describe("a machine's running session", () => {
  it("has earned its settled windows and the host's share of the minutes since", () => {
    // 18 minutes at 18 paise: a window of 15 settled, 3 minutes accrued.
    expect(data.hostRunning.currentSession?.earnedSoFarPaise).toBe(
      data.hostShare(15 * 18) + data.hostShare(3 * 18),
    );
  });

  it("is what its sessions list says the running attempt earned so far, in whole windows", () => {
    const [first] = data.hostSessionsPage(data.ROG.id).sessions;
    expect(first).toMatchObject({ outcome: "running", minutes: 18, endedAt: null });
    expect(first!.earnedPaise).toBe(data.hostShare(15 * 18));
  });

  it("pays each lost session net of what was given back", () => {
    const lost = data.hostSessionsPage(data.ROG.id).sessions.find((session) => session.outcome === "lost")!;
    expect(lost.givenBackPaise).toBe(data.hostShare(4 * 18));
    expect(lost.earnedPaise).toBe(data.earnedFor(30, 18) - lost.givenBackPaise);
  });
});

describe("settling in windows", () => {
  it("splits 41 minutes into 15, 15 and 11, and rounds each window's share down", () => {
    expect(data.windows(41)).toEqual([15, 15, 11]);
    expect(data.earnedFor(41, 18)).toBe(216 + 216 + 158);
  });
});

describe("a machine's rating", () => {
  it("is the mean of its renters' stars to one decimal place, from at least 5 of them", () => {
    expect(data.ratingOf(data.ROG_STARS)).toEqual({ averageStars: 4.6, countAtLeast: 20 });
    expect(data.hostRunning.rating).toEqual(data.ratingOf(data.ROG_STARS));
    expect(data.hostPaused.rating).toBeUndefined();
    expect(data.ROG_STARS.length).toBeGreaterThanOrEqual(5);
  });
});

describe("the earnings after a report's refund", () => {
  const after = data.withReportRefund();
  const entries = after.history.entries;

  it("show the refund as a negative entry of its own, only the host's part of it, beside the session it came from", () => {
    const refund = entries.find((entry) => entry.kind === "refunded_to_renter")!;
    expect(refund.label).toBe("Refunded to renter");
    expect(refund.amountPaise).toBe(-trust.REFUND_SPLIT.fromHost);
    expect(data.earningsEntries.some((entry) => entry.sessionId === refund.sessionId)).toBe(true);
  });

  it("hold the earnings of one session from payout, and no other", () => {
    const held = entries.filter((entry) => entry.payoutHeld);
    expect(held.length).toBeGreaterThan(0);
    expect(new Set(held.map((entry) => entry.sessionId)).size).toBe(1);
  });

  it("are still the sum of their entries, in all and for each machine, less the refund", () => {
    expect(entries.length).toBeLessThanOrEqual(20);
    const sum = entries.reduce((total, entry) => total + entry.amountPaise, 0);
    expect(after.totalPaise).toBe(sum);
    expect(after.totalPaise).toBe(data.earnings.totalPaise - trust.REFUND_SPLIT.fromHost);
    expect(after.machines.reduce((total, machine) => total + machine.earnedPaise, 0)).toBe(sum);
  });
});
