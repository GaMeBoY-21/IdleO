import { describe, expect, it, vi } from "vitest";
import { AuthStore } from "./store";
import type { AuthAdapter, AuthState } from "./types";

function fakeAdapter() {
  let listener: (state: AuthState) => void = () => undefined;
  const adapter: AuthAdapter = {
    watch: (onChange) => {
      listener = onChange;
      onChange({ status: "signed-in", user: { uid: "u1", email: "asha@example.com" } });
      return () => undefined;
    },
    signInWithGoogle: vi.fn(),
    sendEmailLink: vi.fn(),
    completeSignIn: vi.fn(),
    isSignInReturn: () => false,
    getIdToken: vi.fn(async () => "token"),
    signOut: vi.fn(async () => listener({ status: "signed-out", reason: null })),
  };
  return adapter;
}

describe("AuthStore", () => {
  it("reports the adapter's user once started", async () => {
    const store = new AuthStore(async () => fakeAdapter());
    expect(store.getState()).toEqual({ status: "loading" });
    await store.start();
    expect(store.getState()).toEqual({ status: "signed-in", user: { uid: "u1", email: "asha@example.com" } });
  });

  it("signs out with the reason, even though the provider reports none", async () => {
    const adapter = fakeAdapter();
    const store = new AuthStore(async () => adapter);
    const seen: AuthState[] = [];
    store.subscribe(() => seen.push(store.getState()));
    await store.signOut("expired");
    expect(adapter.signOut).toHaveBeenCalled();
    expect(store.getState()).toEqual({ status: "signed-out", reason: "expired" });
    expect(seen.filter((state) => state.status === "signed-out")).toEqual([
      { status: "signed-out", reason: "expired" },
      { status: "signed-out", reason: "expired" },
    ]);
  });

  it("asks the adapter for tokens, refreshed when asked", async () => {
    const adapter = fakeAdapter();
    const store = new AuthStore(async () => adapter);
    expect(await store.getIdToken(true)).toBe("token");
    expect(adapter.getIdToken).toHaveBeenCalledWith(true);
  });
});
