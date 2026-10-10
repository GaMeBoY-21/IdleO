import { createAsyncStoragePersister } from "@tanstack/query-async-storage-persister";
import { persistQueryClient } from "@tanstack/query-persist-client-core";
import type { QueryClient } from "@tanstack/react-query";
import { del, get, set } from "idb-keyval";

/**
 * What the renter saw last time, shown at once when they come back, then refreshed (web/DESIGN.md principles 3
 * and 5). Only these are kept: the home's (the user, the wallet, the session list and the template names) and an
 * owner's (their machines, each machine, their earnings; ADR 0023), so the Hosting tab's figure and a machine's
 * name don't wait on the network for their first paint, which a cold start would otherwise make a second slower
 * than the budget. A machine's telemetry, price band and sessions are never kept. The
 * cache is kept in this browser's IndexedDB for the signed-in user only: it's restored only once sign-in has said
 * who that is, a different user's cache is thrown away, it's dropped after a day, and signing out removes it.
 * Restored figures always show when they're from until fresh ones arrive, and they're never taken as fresh: however
 * recent, they're refetched once the screen has shown them (refreshRestored), so the network never holds it back.
 */
const KEPT = new Set(["me", "wallet", "sessions", "templates", "hosts", "host", "earnings"]);

/** A kept query: one of KEPT's, but not what hangs off a machine (its telemetry, price band and sessions). */
const isKept = (key: readonly unknown[]) =>
  KEPT.has(String(key[0])) && (key[0] !== "host" || key.length === 2);
const MAX_AGE_MS = 24 * 60 * 60_000;

const persister = createAsyncStoragePersister({
  key: "idleo.home-cache",
  storage: {
    getItem: (key) => get<string>(key).then((value) => value ?? null),
    setItem: set,
    removeItem: del,
  },
  throttleTime: 1_000,
});

let active: { uid: string; stop: () => void } | null = null;
let unrefreshed = false;

/** Restores the signed-in user's last figures into the client, and keeps saving them; safe to call again. */
export async function restoreFor(client: QueryClient, uid: string): Promise<void> {
  if (active?.uid === uid) {
    return;
  }
  active?.stop();
  const [stop, restored] = persistQueryClient({
    queryClient: client,
    persister,
    buster: uid,
    maxAge: MAX_AGE_MS,
    dehydrateOptions: {
      shouldDehydrateQuery: (query) => query.state.status === "success" && isKept(query.queryKey),
    },
  });
  active = { uid, stop };
  await restored.catch(() => persister.removeClient());
  unrefreshed = true;
}

/** Marks what restoreFor brought back as stale, refetching what's on screen; once per restore. */
export function refreshRestored(client: QueryClient): void {
  if (!unrefreshed) {
    return;
  }
  unrefreshed = false;
  void client.invalidateQueries({ predicate: (query) => isKept(query.queryKey) });
}

/** Signing out forgets the figures, in memory and in the browser. */
export async function forget(): Promise<void> {
  active?.stop();
  active = null;
  unrefreshed = false;
  await persister.removeClient();
}
