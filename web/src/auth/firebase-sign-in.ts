import {
  type Auth,
  browserPopupRedirectResolver,
  GoogleAuthProvider,
  sendSignInLinkToEmail,
  signInWithEmailLink,
  signInWithPopup,
} from "firebase/auth";
import { firebaseErrorCode } from "./firebase-errors";
import { AuthError } from "./types";

/** The sign-in actions, loaded only on the sign-in screens (ADR 0018). */

export async function signInWithGoogle(auth: Auth): Promise<void> {
  try {
    await signInWithPopup(auth, new GoogleAuthProvider(), browserPopupRedirectResolver);
  } catch (error) {
    throw firebaseErrorCode(error);
  }
}

export async function sendEmailLink(auth: Auth, email: string, returnUrl: string): Promise<void> {
  try {
    await sendSignInLinkToEmail(auth, email, { url: returnUrl, handleCodeInApp: true });
  } catch (error) {
    throw firebaseErrorCode(error);
  }
}

export async function completeEmailLink(auth: Auth, email: string, url: string): Promise<void> {
  try {
    await signInWithEmailLink(auth, email, url);
  } catch (error) {
    const mapped = firebaseErrorCode(error);
    // An address that doesn't match the link comes back as invalid-email.
    throw mapped.code === "invalid-email" ? new AuthError("wrong-email", error) : mapped;
  }
}
