import { initializeApp } from "firebase/app";
import {
  type Auth,
  indexedDBLocalPersistence,
  initializeAuth,
  isSignInWithEmailLink,
  onIdTokenChanged,
  signOut,
} from "firebase/auth";
import { config } from "@/config";
import { AuthError, type AuthAdapter, type AuthState, type CompletedSignIn } from "./types";

/**
 * Sign-in through Google Identity Platform (Firebase Auth), for staging and production (ADRs 0009, 0018): Google,
 * and a passwordless email link. The only file, with firebase-sign-in.ts, that may import the Firebase SDK.
 *
 * The SDK keeps the session where it keeps it (IndexedDB) and refreshes ID tokens itself; our code never stores a
 * token. The popup and email-link code loads only when someone signs in, so the signed-in screens don't carry it.
 */

/** The address an email link was sent to, so the same device needn't ask again. Not a credential. */
const EMAIL_KEY = "idleo.emailForSignIn";

function rememberEmail(email: string | null): void {
  try {
    if (email === null) {
      localStorage.removeItem(EMAIL_KEY);
    } else {
      localStorage.setItem(EMAIL_KEY, email);
    }
  } catch {
    // Storage is blocked: the person is asked for their email when they open the link.
  }
}

function rememberedEmail(): string | null {
  try {
    return localStorage.getItem(EMAIL_KEY);
  } catch {
    return null;
  }
}

export function createFirebaseAdapter(): AuthAdapter {
  const app = initializeApp({
    apiKey: config.firebase.apiKey,
    authDomain: config.firebase.authDomain,
    projectId: config.firebase.projectId,
  });
  const auth: Auth = initializeAuth(app, { persistence: indexedDBLocalPersistence });

  return {
    watch(onChange: (state: AuthState) => void) {
      return onIdTokenChanged(auth, (user) =>
        onChange(
          user
            ? { status: "signed-in", user: { uid: user.uid, email: user.email } }
            : { status: "signed-out", reason: null },
        ),
      );
    },
    async signInWithGoogle() {
      const { signInWithGoogle } = await import("./firebase-sign-in");
      await signInWithGoogle(auth);
    },
    async sendEmailLink(email, returnUrl) {
      const { sendEmailLink } = await import("./firebase-sign-in");
      await sendEmailLink(auth, email, returnUrl);
      rememberEmail(email);
      return { kind: "sent" };
    },
    isSignInReturn(url) {
      return isSignInWithEmailLink(auth, url);
    },
    async completeSignIn(url, email): Promise<CompletedSignIn> {
      if (!isSignInWithEmailLink(auth, url)) {
        throw new AuthError("not-a-link");
      }
      const address = email ?? rememberedEmail();
      if (address === null) {
        // Opened on another device, or storage was cleared: ask which address the link was sent to.
        return { kind: "needs-email" };
      }
      const { completeEmailLink } = await import("./firebase-sign-in");
      await completeEmailLink(auth, address, url);
      rememberEmail(null);
      return { kind: "signed-in" };
    },
    async getIdToken(forceRefresh) {
      return (await auth.currentUser?.getIdToken(forceRefresh)) ?? null;
    },
    async signOut() {
      await signOut(auth);
    },
  };
}
