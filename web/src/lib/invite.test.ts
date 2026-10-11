import { describe, expect, it } from "vitest";
import { formatInviteCode, isCompleteInviteCode } from "./invite";

describe("an invite code as it's typed", () => {
  it("shows nothing for nothing", () => {
    expect(formatInviteCode("")).toBe("");
    expect(formatInviteCode("  - ")).toBe("");
  });

  it("puts the prefix and groups of four around what's typed", () => {
    expect(formatInviteCode("7")).toBe("IDLE-7");
    expect(formatInviteCode("7k2q")).toBe("IDLE-7K2Q");
    expect(formatInviteCode("7k2q9")).toBe("IDLE-7K2Q-9");
    expect(formatInviteCode("7k2q9xmd")).toBe("IDLE-7K2Q-9XMD");
  });

  it("lets the prefix be typed too, a letter at a time", () => {
    expect(formatInviteCode("i")).toBe("I");
    expect(formatInviteCode("id")).toBe("ID");
    expect(formatInviteCode("idl")).toBe("IDL");
    expect(formatInviteCode("idle")).toBe("IDLE-");
    expect(formatInviteCode("idle7")).toBe("IDLE-7");
  });

  it("makes a pasted code the same however it was written", () => {
    for (const pasted of [
      "IDLE-7K2Q-9XMD",
      "idle 7k2q 9xmd",
      "  idle7k2q9xmd ",
      "7K2Q9XMD",
      "IDLE–7K2Q–9XMD",
    ]) {
      expect(formatInviteCode(pasted)).toBe("IDLE-7K2Q-9XMD");
    }
  });

  it("stops at eight characters", () => {
    expect(formatInviteCode("IDLE-7K2Q-9XMDZZZ")).toBe("IDLE-7K2Q-9XMD");
  });

  it("is complete only with all eight characters", () => {
    expect(isCompleteInviteCode("IDLE-7K2Q-9XM")).toBe(false);
    expect(isCompleteInviteCode("")).toBe(false);
    expect(isCompleteInviteCode("7k2q9xmd")).toBe(true);
    expect(isCompleteInviteCode("IDLE-7K2Q-9XMD")).toBe(true);
  });
});
