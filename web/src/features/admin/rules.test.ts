import { describe, expect, it } from "vitest";
import {
  isRecoveryCode,
  isTotp,
  reasonProblem,
  recoveryDigits,
  refundProblem,
  totpDigits,
  witnessSummary,
  type WitnessedMinute,
} from "./rules";

describe("the reason rule", () => {
  it("wants at least 5 characters once the spaces around it are gone", () => {
    expect(reasonProblem("")).toMatch(/at least 5 characters/);
    expect(reasonProblem("spam")).toMatch(/at least 5 characters/);
    expect(reasonProblem("   spam   ")).toMatch(/at least 5 characters/);
    expect(reasonProblem("fraud")).toBeNull();
    expect(reasonProblem("  card testing  ")).toBeNull();
  });

  it("keeps it to 500 characters, as the server does", () => {
    expect(reasonProblem("x".repeat(500))).toBeNull();
    expect(reasonProblem("x".repeat(501))).toMatch(/500 characters/);
  });
});

describe("the code field", () => {
  it("takes 6 digits, with the space or dash an authenticator shows between groups", () => {
    expect(totpDigits("123 456")).toBe("123456");
    expect(totpDigits("123-456")).toBe("123456");
    expect(isTotp("123456")).toBe(true);
    expect(isTotp(" 123 456 ")).toBe(true);
  });

  it("refuses anything that isn't 6 digits", () => {
    expect(isTotp("12345")).toBe(false);
    expect(isTotp("1234567")).toBe(false);
    expect(isTotp("12a456")).toBe(false);
    expect(isTotp("")).toBe(false);
    expect(totpDigits("12345678")).toBe("123456");
  });

  it("takes a recovery code in any case, with or without its dash", () => {
    expect(recoveryDigits("k7q2m-9xd4p")).toBe("K7Q2M9XD4P");
    expect(isRecoveryCode("k7q2m-9xd4p")).toBe(true);
    expect(isRecoveryCode("K7Q2M9XD4P")).toBe(true);
    expect(isRecoveryCode("K7Q2M-9XD4")).toBe(false);
    // The alphabet has no 0, O, 1 or I, so they're never in a code.
    expect(isRecoveryCode("K7Q2M-9XD4O")).toBe(false);
  });
});

describe("a refund's amount", () => {
  it("is above zero and at most what the session cost", () => {
    expect(refundProblem(null, 300)).toMatch(/above ₹0/);
    expect(refundProblem(0, 300)).toMatch(/above ₹0/);
    expect(refundProblem(1, 300)).toBeNull();
    expect(refundProblem(300, 300)).toBeNull();
    expect(refundProblem(301, 300)).toMatch(/more than the session cost/);
  });
});

describe("a session's minutes beside a report", () => {
  const minute = (overrides: Partial<WitnessedMinute> = {}): WitnessedMinute => ({
    agentReported: true,
    leaseAlive: true,
    agentAttempt: 1,
    leaseAttempt: 1,
    billed: true,
    waived: false,
    ...overrides,
  });

  it("counts what each witness saw, and what both did", () => {
    const summary = witnessSummary([
      minute(),
      minute(),
      minute({ leaseAlive: false, leaseAttempt: null, billed: false }),
      minute({ agentReported: false, agentAttempt: null, billed: false }),
      minute({ agentAttempt: 1, leaseAttempt: 2, billed: false }),
    ]);
    expect(summary).toMatchObject({ total: 5, agent: 4, lease: 4, both: 2, charged: 2, waived: 0 });
  });

  it("lists only the minutes that weren't charged in the plain way: one witness, from different attempts, or waived", () => {
    const plain = minute();
    const oneWitness = minute({ leaseAlive: false, billed: false });
    const waived = minute({ waived: true });
    expect(witnessSummary([plain, oneWitness, waived]).unusual).toEqual([oneWitness, waived]);
    expect(witnessSummary([plain]).unusual).toEqual([]);
  });
});
