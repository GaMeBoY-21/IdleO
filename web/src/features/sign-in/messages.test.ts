import { describe, expect, it } from "vitest";
import { AuthError } from "@/auth/types";
import { signInMessage } from "./messages";

describe("sign-in messages", () => {
  it("say what happened and what to do next", () => {
    expect(signInMessage(new AuthError("link-expired"))).toBe(
      "This sign-in link has expired. Links work for an hour, so send yourself a new one.",
    );
    expect(signInMessage(new AuthError("link-used"))).toMatch(/already been used/);
    expect(signInMessage(new AuthError("popup-blocked"))).toMatch(/blocked the Google sign-in window/);
  });

  it("say nothing when the person just closed the popup", () => {
    expect(signInMessage(new AuthError("popup-closed"))).toBeNull();
  });

  it("never show a raw error", () => {
    expect(signInMessage(new Error("auth/internal-error: stack…"))).toMatch(
      /Something went wrong signing you in/,
    );
  });
});
