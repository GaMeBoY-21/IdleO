import { AuthError, type AuthErrorCode } from "@/auth";

/** What went wrong signing in, and what to do next (web/DESIGN.md principle 4). Null: nothing to say. */
const MESSAGES: Record<AuthErrorCode, string | null> = {
  "popup-blocked":
    "Your browser blocked the Google sign-in window. Allow pop-ups for this site, or use an email link instead.",
  "popup-closed": null,
  "link-expired": "This sign-in link has expired. Links work for an hour, so send yourself a new one.",
  "link-used": "This sign-in link has already been used, or it's incomplete. Send yourself a new one.",
  "not-a-link": "That isn't a sign-in link. Send yourself a new one.",
  "wrong-email": "That isn't the address this link was sent to. Check it, or send a new link.",
  "invalid-email": "That doesn't look like an email address. Check it and try again.",
  network: "We couldn't reach the sign-in service. Check your connection and try again.",
  unknown: "Something went wrong signing you in. Try again, or use the other way to sign in.",
};

export function signInMessage(error: unknown): string | null {
  return error instanceof AuthError ? MESSAGES[error.code] : MESSAGES.unknown;
}
