import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Route, Router } from "wouter";
import { memoryLocation } from "wouter/memory-location";
import type { AuthState } from "@/auth";
import { RequireAuth } from "./RequireAuth";

const auth = vi.hoisted((): { state: AuthState; listeners: Set<() => void> } => ({
  state: { status: "loading" },
  listeners: new Set(),
}));
const cache = vi.hoisted(() => ({
  kept: null as string | null,
  restoreFor: vi.fn<(client: unknown, uid: string) => Promise<void>>(async () => undefined),
}));

vi.mock("@/auth", async () => {
  const { useSyncExternalStore } = await import("react");
  return {
    useAuth: () =>
      useSyncExternalStore(
        (listener: () => void) => {
          auth.listeners.add(listener);
          return () => auth.listeners.delete(listener);
        },
        () => auth.state,
      ),
  };
});
vi.mock("@/api/persisted-cache", () => ({
  keptUser: async () => cache.kept,
  restoreFor: cache.restoreFor,
  refreshRestored: vi.fn(),
}));

function setAuth(state: AuthState) {
  act(() => {
    auth.state = state;
    auth.listeners.forEach((listener) => listener());
  });
}

function open() {
  const location = memoryLocation({ path: "/hosting/", record: true });
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Router hook={location.hook}>
        <Route path="/hosting/">
          <RequireAuth fallback={<p>skeleton</p>}>
            <p>the screen</p>
          </RequireAuth>
        </Route>
      </Router>
    </QueryClientProvider>,
  );
  return location;
}

describe("RequireAuth", () => {
  beforeEach(() => {
    auth.state = { status: "loading" };
    cache.kept = null;
    cache.restoreFor.mockClear();
  });

  it("draws the screen from the figures kept for whoever was here last, before sign-in has loaded", async () => {
    cache.kept = "asha";
    open();
    expect(await screen.findByText("the screen")).toBeInTheDocument();
    expect(cache.restoreFor).toHaveBeenCalledWith(expect.anything(), "asha");
    expect(auth.state.status).toBe("loading");
  });

  it("waits for sign-in when nothing is kept", async () => {
    open();
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByText("skeleton")).toBeInTheDocument();
    setAuth({ status: "signed-in", user: { uid: "asha", email: "asha@example.com" } });
    expect(await screen.findByText("the screen")).toBeInTheDocument();
  });

  it("takes the kept figures away and goes to sign-in when sign-in says no one is here", async () => {
    cache.kept = "asha";
    const location = open();
    await screen.findByText("the screen");
    setAuth({ status: "signed-out", reason: "expired" });
    expect(screen.queryByText("the screen")).not.toBeInTheDocument();
    await waitFor(() => expect(location.history.at(-1)).toMatch(/^\/sign-in\/\?.*expired=1/));
  });

  it("restores for the person sign-in names when that's someone else", async () => {
    cache.kept = "asha";
    open();
    await screen.findByText("the screen");
    setAuth({ status: "signed-in", user: { uid: "ravi", email: "ravi@example.com" } });
    await waitFor(() => expect(cache.restoreFor).toHaveBeenLastCalledWith(expect.anything(), "ravi"));
  });
});
