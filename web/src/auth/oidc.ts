import { UserManager, type User } from "oidc-client-ts";
import { config } from "@/config";
import { IndexedDbStore } from "./indexeddb-store";
import { AuthError, type AuthAdapter, type AuthState, type CompletedSignIn } from "./types";

/**
 * Sign-in against the local development sign-in server (ADRs 0013, 0018), for local runs and the end-to-end
 * test only: a build for staging or production refuses this adapter (src/build-settings.ts). The Firebase
 * emulator would issue unsigned tokens, which the control plane rightly refuses; this server signs real ones.
 *
 * It signs in anyone as anyone, so it only signs in invented @example.com addresses. Both buttons work: the
 * email link signs in that address at once, without sending anything; Google signs in local-google@example.com.
 * The address's local part becomes the user (the server's `login_hint` mapping in control-plane/local-sign-in.json).
 */

const DOMAIN = "@example.com";
const GOOGLE_USER = "local-google";

function stateOf(user: User | null): AuthState {
  if (!user || user.expired) {
    return { status: "signed-out", reason: null };
  }
  const email = typeof user.profile.email === "string" ? user.profile.email : null;
  return { status: "signed-in", user: { uid: user.profile.sub, email } };
}

export function createOidcAdapter(): AuthAdapter {
  const manager = new UserManager({
    authority: config.oidc.issuer,
    client_id: config.oidc.clientId,
    redirect_uri: `${window.location.origin}/sign-in/finish/`,
    response_type: "code",
    scope: "openid",
    loadUserInfo: false,
    automaticSilentRenew: false,
    userStore: new IndexedDbStore(),
  });

  // auth_time is when this sign-in happened, as Identity Platform's tokens carry it; the development server copies it
  // into the token (control-plane/local-sign-in.json), so deleting an account can ask for a fresh sign-in locally too.
  const redirect = (localPart: string) =>
    manager.signinRedirect({
      login_hint: localPart,
      extraTokenParams: { login_hint: localPart, auth_time: String(Math.floor(Date.now() / 1000)) },
    });

  return {
    watch(onChange) {
      let stopped = false;
      const report = (user: User | null) => {
        if (!stopped) {
          onChange(stateOf(user));
        }
      };
      void manager.getUser().then(report);
      const loaded = (user: User) => report(user);
      const unloaded = () => report(null);
      manager.events.addUserLoaded(loaded);
      manager.events.addUserUnloaded(unloaded);
      manager.events.addAccessTokenExpired(unloaded);
      return () => {
        stopped = true;
        manager.events.removeUserLoaded(loaded);
        manager.events.removeUserUnloaded(unloaded);
        manager.events.removeAccessTokenExpired(unloaded);
      };
    },
    async signInWithGoogle() {
      await redirect(GOOGLE_USER);
    },
    async sendEmailLink(email) {
      const address = email.trim().toLowerCase();
      if (!address.endsWith(DOMAIN) || address.length === DOMAIN.length) {
        throw new AuthError("invalid-email");
      }
      await redirect(address.slice(0, -DOMAIN.length));
      return { kind: "redirecting" };
    },
    isSignInReturn(url) {
      const params = new URL(url).searchParams;
      return params.has("code") && params.has("state");
    },
    async completeSignIn(url): Promise<CompletedSignIn> {
      try {
        await manager.signinRedirectCallback(url);
        return { kind: "signed-in" };
      } catch (error) {
        throw new AuthError("link-used", error);
      }
    },
    async getIdToken() {
      // The local server's tokens last an hour and aren't refreshed; an expired one means signing in again.
      const user = await manager.getUser();
      return user && !user.expired && user.id_token ? user.id_token : null;
    },
    async signOut() {
      await manager.removeUser();
    },
  };
}
