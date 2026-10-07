/** Sign-in, whatever provider is behind it (ADR 0018): the app sees only these types. */

export interface AuthUser {
  uid: string;
  email: string | null;
}

export type SignedOutReason = "signed-out" | "expired";

export type AuthState =
  | { status: "loading" }
  | { status: "signed-out"; reason: SignedOutReason | null }
  | { status: "signed-in"; user: AuthUser };

export type AuthErrorCode =
  | "popup-blocked"
  | "popup-closed"
  | "link-expired"
  | "link-used"
  | "not-a-link"
  | "wrong-email"
  | "invalid-email"
  | "network"
  | "unknown";

export class AuthError extends Error {
  readonly code: AuthErrorCode;

  constructor(code: AuthErrorCode, cause?: unknown) {
    super(code, { cause });
    this.name = "AuthError";
    this.code = code;
  }
}

/** What finishing a sign-in from a returned URL (an email link, or a redirect) did. */
export type CompletedSignIn = { kind: "signed-in" } | { kind: "needs-email" };

/** What asking for an email link did: sent, or (the local mock only) signed in at once. */
export type EmailLinkRequest = { kind: "sent" } | { kind: "redirecting" };

/**
 * One provider's sign-in. Exactly one implementation is in a build: Firebase in staging and production, the OIDC
 * mock against the local sign-in server for local runs and the end-to-end test.
 */
export interface AuthAdapter {
  /** Watches the signed-in user; returns the way to stop. Reports the current state at once. */
  watch(onChange: (state: AuthState) => void): () => void;
  signInWithGoogle(): Promise<void>;
  sendEmailLink(email: string, returnUrl: string): Promise<EmailLinkRequest>;
  /** Finishes a sign-in from the URL the provider sent the person back to; `email` when they've just typed it. */
  completeSignIn(url: string, email: string | null): Promise<CompletedSignIn>;
  /** Whether this URL is one the provider sent back to finish signing in. */
  isSignInReturn(url: string): boolean;
  /** The current ID token, refreshed first when `forceRefresh` or when it's about to expire; null if signed out. */
  getIdToken(forceRefresh: boolean): Promise<string | null>;
  signOut(): Promise<void>;
}
