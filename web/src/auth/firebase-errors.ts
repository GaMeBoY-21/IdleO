import { AuthError } from "./types";

/** Firebase Auth's error codes, as the app's own (ADR 0018). */
export function firebaseErrorCode(error: unknown): AuthError {
  const code = typeof error === "object" && error && "code" in error ? String(error.code) : "";
  switch (code) {
    case "auth/popup-blocked":
      return new AuthError("popup-blocked", error);
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
    case "auth/user-cancelled":
      return new AuthError("popup-closed", error);
    case "auth/expired-action-code":
      return new AuthError("link-expired", error);
    case "auth/invalid-action-code":
      return new AuthError("link-used", error);
    case "auth/invalid-email":
      return new AuthError("invalid-email", error);
    case "auth/network-request-failed":
      return new AuthError("network", error);
    default:
      return new AuthError("unknown", error);
  }
}
