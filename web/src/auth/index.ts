import { useSyncExternalStore } from "react";
import { config } from "@/config";
import { AuthStore } from "./store";
import type { AuthAdapter, AuthState, CompletedSignIn, EmailLinkRequest, SignedOutReason } from "./types";

export { AuthError } from "./types";
export type { AuthErrorCode, AuthState, AuthUser } from "./types";

/**
 * Sign-in for the whole app (ADR 0018): the one adapter this build has, behind four things the rest of the app
 * uses: signIn, signOut, getIdToken and useAuth. Only this folder knows which provider it is; the build writes in
 * VITE_AUTH, so the other adapter's code isn't in the bundle at all.
 */
async function loadAdapter(): Promise<AuthAdapter> {
  if (import.meta.env.VITE_AUTH === "oidc-mock") {
    const { createOidcAdapter } = await import("./oidc");
    return createOidcAdapter();
  }
  const { createFirebaseAdapter } = await import("./firebase");
  return createFirebaseAdapter();
}

export const authStore = new AuthStore(loadAdapter);

const loading: AuthState = { status: "loading" };

export function useAuth(): AuthState {
  return useSyncExternalStore(authStore.subscribe, authStore.getState, () => loading);
}

export const signIn = {
  async withGoogle(): Promise<void> {
    await (await authStore.adapterNow()).signInWithGoogle();
  },
  async withEmailLink(email: string): Promise<EmailLinkRequest> {
    const returnUrl = `${window.location.origin}/sign-in/finish/`;
    return (await authStore.adapterNow()).sendEmailLink(email, returnUrl);
  },
  async isReturn(url: string): Promise<boolean> {
    return (await authStore.adapterNow()).isSignInReturn(url);
  },
  async complete(url: string, email: string | null): Promise<CompletedSignIn> {
    return (await authStore.adapterNow()).completeSignIn(url, email);
  },
};

export function signOut(reason: SignedOutReason = "signed-out"): Promise<void> {
  return authStore.signOut(reason);
}

export function getIdToken(forceRefresh = false): Promise<string | null> {
  return authStore.getIdToken(forceRefresh);
}

/** Which provider this build signs in with; the sign-in screen says when it's the local mock. */
export const authProvider = config.auth;
