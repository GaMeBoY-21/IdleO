import { describe, expect, it } from "vitest";
import * as data from "../../e2e/fixtures";
import { formatPaise } from "@/lib/money";

/**
 * The invented data adds up on every screen (e2e/fixtures.ts): the review found "on hold ₹0.60" beside a session
 * "₹13.60 so far", and a welcome offering "₹499.40 to start with". These rules hold for every account.
 */
describe.each(Object.entries(data.accounts))("the %s account", (_name, account) => {
  it("has a total of available plus on hold", () => {
    expect(account.wallet.balancePaise).toBe(account.wallet.availablePaise + account.wallet.reservedPaise);
  });

  it("holds exactly what its live sessions hold, each covering what it has used and not settled", () => {
    const live = account.sessions.filter((session) => !session.settled);
    expect(account.wallet.reservedPaise).toBe(live.reduce((sum, session) => sum + session.reservedPaise, 0));
    for (const session of live) {
      expect(session.reservedPaise).toBeGreaterThanOrEqual(session.accruedPaise);
      expect(session.chargedPaise + session.accruedPaise).toBeLessThanOrEqual(session.maxSpendPaise);
    }
  });

  it("holds and accrues nothing for a settled session", () => {
    for (const session of account.sessions.filter((s) => s.settled)) {
      expect([session.reservedPaise, session.accruedPaise]).toEqual([0, 0]);
      expect(session.endedAt).not.toBeNull();
    }
  });

  it("started with a round grant, and its history adds up to the total", () => {
    expect(account.grant % 10_000).toBe(0);
    const grants = account.history.filter((entry) => entry.kind === "free_credit");
    expect(grants.map((entry) => entry.amountPaise)).toEqual(account.grant === 0 ? [] : [account.grant]);
    const sum = account.history.reduce(
      (total, entry) => total + (entry.direction === "credit" ? entry.amountPaise : -entry.amountPaise),
      0,
    );
    expect(sum).toBe(account.wallet.balancePaise);
  });

  it("charges each session what its history entries say", () => {
    for (const session of account.sessions) {
      const entries = account.history.filter((entry) => entry.reference.id === session.id);
      const net = entries.reduce(
        (total, entry) => total + (entry.direction === "debit" ? entry.amountPaise : -entry.amountPaise),
        0,
      );
      expect(net).toBe(session.chargedPaise);
    }
  });
});

describe("the sessions on their own", () => {
  it.each([data.finding, data.movedLive, data.lowCreditRunning])("follow the same hold rule", (session) => {
    expect(session.reservedPaise).toBeGreaterThanOrEqual(session.accruedPaise);
  });

  it("a new account's credit is exactly the grant", () => {
    expect(data.fresh.wallet).toEqual({
      balancePaise: data.GRANT,
      reservedPaise: 0,
      availablePaise: data.GRANT,
      boughtPaise: 0,
      freePaise: data.GRANT,
      freeExpiresAt: null,
      refundingPaise: 0,
      frozenPaise: 0,
    });
  });

  it("a quote holds 30 minutes at the maximum price, within the cap", () => {
    expect(data.quote(18, 50_000)?.holdPaise).toBe(540);
    expect(data.quote(18, 300)?.holdPaise).toBe(300);
  });

  it("without a ceiling, a quote's is the highest price among the GPUs free now that fit, or none", () => {
    expect(data.quote(undefined, 50_000)?.ceilingPaisePerMin).toBe(18);
    expect(data.quote(undefined, 50_000, data.asha.wallet, data.availability, 16_000)).toBeNull();
    expect(data.quote(undefined, 50_000, data.asha.wallet, data.noAvailability)).toBeNull();
  });
});

describe("the notifications", () => {
  const { inbox, completed, failed, running } = data;

  it("are newest first, with ids in time order, and `unread` of them unread, newest first", () => {
    const page = inbox(3);
    expect(page.items.map((item) => item.createdAt)).toEqual(
      [...page.items.map((item) => item.createdAt)].sort().reverse(),
    );
    expect(page.items.map((item) => item.id)).toEqual(
      [...page.items.map((item) => item.id)].sort().reverse(),
    );
    expect(page.items.map((item) => item.read)).toEqual([false, false, false, true]);
    expect(page.unread).toBe(3);
    expect(inbox(0).items.every((item) => item.read)).toBe(true);
  });

  it("name what their session cost, as the session itself says", () => {
    const { items } = inbox();
    const finished = items.find((item) => item.kind === "session-completed")!;
    expect(finished.body).toContain(formatPaise(completed.chargedPaise));
    expect(finished.link).toBe(`/session/?id=${completed.id}#outputs`);
    const didNotFinish = items.find((item) => item.kind === "session-failed")!;
    expect(didNotFinish.body).toContain(formatPaise(failed.chargedPaise));
    expect(items.find((item) => item.kind === "low-credit")!.link).toBe(`/session/?id=${running.id}`);
  });
});

describe("a reported session's refund", () => {
  const { reportedSession, REPORT_REFUND, refunded } = data;

  it("is a credit of its own in the history, and the session's charge is net of it", () => {
    const credit = refunded.history.find((entry) => entry.kind === "dispute_refund")!;
    expect(credit).toMatchObject({ direction: "credit", amountPaise: REPORT_REFUND });
    expect(credit.reference).toEqual({ type: "dispute_refund", id: reportedSession.id });
    // 30 minutes at 10 paise, less the refund.
    expect(reportedSession.chargedPaise).toBe(30 * 10 - REPORT_REFUND);
  });
});
