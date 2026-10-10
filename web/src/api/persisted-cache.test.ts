import { QueryClient } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

const stored = new Map<string, string>();
vi.mock("idb-keyval", () => ({
  get: async (key: string) => stored.get(key),
  set: async (key: string, value: string) => void stored.set(key, value),
  del: async (key: string) => void stored.delete(key),
}));

const { forget, restoreFor } = await import("./persisted-cache");

const settle = () => new Promise((resolve) => setTimeout(resolve, 1_100));

describe("the kept figures", () => {
  beforeEach(async () => {
    await forget();
    stored.clear();
  });

  it("brings back the same user's figures on their next visit, and only the home's", async () => {
    const first = new QueryClient();
    await restoreFor(first, "asha");
    first.setQueryData(["wallet"], { availablePaise: 49_940 });
    first.setQueryData(["session", "x"], { id: "x" });
    await settle();

    // A new page: a fresh module and a fresh client, with only the browser's store carried over.
    vi.resetModules();
    const fresh = await import("./persisted-cache");
    const next = new QueryClient();
    await fresh.restoreFor(next, "asha");
    expect(next.getQueryData(["wallet"])).toEqual({ availablePaise: 49_940 });
    expect(next.getQueryData(["session", "x"])).toBeUndefined();
    await fresh.forget();
  });

  it("brings back an owner's machines, a machine and their earnings, but nothing that hangs off a machine", async () => {
    const first = new QueryClient();
    await restoreFor(first, "asha");
    first.setQueryData(["hosts"], { hosts: [] });
    first.setQueryData(["host", "h1"], { id: "h1" });
    first.setQueryData(["earnings"], { pages: [], pageParams: [] });
    first.setQueryData(["host", "h1", "telemetry"], { now: null });
    first.setQueryData(["host", "h1", "sessions"], { pages: [] });
    first.setQueryData(["host", "h1", "price-band"], { lowPaisePerMin: 1 });
    await settle();

    vi.resetModules();
    const fresh = await import("./persisted-cache");
    const next = new QueryClient();
    await fresh.restoreFor(next, "asha");
    expect(next.getQueryData(["hosts"])).toEqual({ hosts: [] });
    expect(next.getQueryData(["host", "h1"])).toEqual({ id: "h1" });
    expect(next.getQueryData(["earnings"])).toEqual({ pages: [], pageParams: [] });
    expect(next.getQueryData(["host", "h1", "telemetry"])).toBeUndefined();
    expect(next.getQueryData(["host", "h1", "sessions"])).toBeUndefined();
    expect(next.getQueryData(["host", "h1", "price-band"])).toBeUndefined();
    await fresh.forget();
  });

  it("refreshes restored figures once the screen has shown them, however recent", async () => {
    const first = new QueryClient();
    await restoreFor(first, "asha");
    first.setQueryData(["sessions"], { sessions: [] });
    await settle();

    vi.resetModules();
    const fresh = await import("./persisted-cache");
    const next = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000 } } });
    await fresh.restoreFor(next, "asha");
    const restored = next.getQueryCache().find({ queryKey: ["sessions"] });
    expect(restored?.state.data).toEqual({ sessions: [] });
    expect(restored?.isStale()).toBe(false);
    fresh.refreshRestored(next);
    expect(restored?.isStale()).toBe(true);
    await fresh.forget();
  });

  it("gives a different user nothing of the last one's", async () => {
    const asha = new QueryClient();
    await restoreFor(asha, "asha");
    asha.setQueryData(["wallet"], { availablePaise: 49_940 });
    await settle();
    expect(stored.size).toBe(1);

    const ravi = new QueryClient();
    await restoreFor(ravi, "ravi");
    expect(ravi.getQueryData(["wallet"])).toBeUndefined();
  });

  it("forgets everything on sign-out", async () => {
    const client = new QueryClient();
    await restoreFor(client, "asha");
    client.setQueryData(["wallet"], { availablePaise: 1 });
    await settle();
    expect(stored.size).toBe(1);
    await forget();
    expect(stored.size).toBe(0);
  });
});
