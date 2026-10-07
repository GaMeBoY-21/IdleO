import { describe, expect, it } from "vitest";
import { creditKind, eventReason } from "./labels";

describe("the renter's words", () => {
  it("name every kind of credit movement the API reports", () => {
    expect(creditKind("free_credit")).toBe("Free credit");
    expect(creditKind("session_charge")).toBe("Session charge");
    expect(creditKind("session_refund")).toBe("Refund for lost host time");
  });

  it("explain why a session changed", () => {
    expect(eventReason("max_spend_reached")).toBe("It reached your spending limit");
    expect(eventReason("credit_exhausted")).toBe("Your credit ran out");
    expect(eventReason("some_new_reason")).toBe("Some new reason");
  });
});
