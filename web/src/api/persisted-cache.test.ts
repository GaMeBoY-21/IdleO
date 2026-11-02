import { QueryClient } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

const store = vi.hoisted(() => new Map<string, string>());
vi.mock("idb-keyval", () => ({
  get: async (key: string) => store.get(key),
  set: async (key: string, value: string) => void store.set(key, value),
  del: async (key: string) => void store.delete(key),
}));

const KEY = "idleo.home-cache";
const kept = (buster: string, timestamp: number) =>
  JSON.stringify({ buster, timestamp, clientState: { mutations: [], queries: [] } });

describe("the figures kept from an earlier visit", () => {
  beforeEach(() => {
    store.clear();
    vi.resetModules();
  });

  it("name who they were kept for", async () => {
    store.set(KEY, kept("asha", Date.now() - 60_000));
    const { keptUser } = await import("./persisted-cache");
    expect(await keptUser()).toBe("asha");
  });

  it("name no one when there are none, or they're over a day old", async () => {
    const none = await import("./persisted-cache");
    expect(await none.keptUser()).toBeNull();
    vi.resetModules();
    store.set(KEY, kept("asha", Date.now() - 25 * 60 * 60_000));
    const old = await import("./persisted-cache");
    expect(await old.keptUser()).toBeNull();
  });

  it("are cleared from the screen when sign-in names someone else", async () => {
    store.set(KEY, kept("asha", Date.now() - 60_000));
    const { restoreFor } = await import("./persisted-cache");
    const client = new QueryClient();
    await restoreFor(client, "asha");
    client.setQueryData(["wallet"], { availablePaise: 500 });
    await restoreFor(client, "ravi");
    expect(client.getQueryData(["wallet"])).toBeUndefined();
  });
});
