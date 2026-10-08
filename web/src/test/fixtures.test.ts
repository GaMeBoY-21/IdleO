import { describe, expect, it } from "vitest";
import * as data from "../../e2e/fixtures";

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
    });
  });

  it("a quote holds 30 minutes at the maximum price, within the cap", () => {
    expect(data.quote(18, 50_000).holdPaise).toBe(540);
    expect(data.quote(18, 300).holdPaise).toBe(300);
  });
});
