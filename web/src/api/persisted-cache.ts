import { createAsyncStoragePersister } from "@tanstack/query-async-storage-persister";
import { persistQueryClient } from "@tanstack/query-persist-client-core";
import type { QueryClient } from "@tanstack/react-query";
import { del, get, set } from "idb-keyval";

/**
 * What the renter saw last time, shown at once when they come back, then refreshed (web/DESIGN.md principles 3
 * and 5). Only the home's figures are kept: the user, the wallet, the session list and the template names. The
 * cache is kept in this browser's IndexedDB for the signed-in user only: it's restored only once sign-in has said
 * who that is, a different user's cache is thrown away, it's dropped after a day, and signing out removes it.
 * Restored figures always show when they're from until fresh ones arrive.
 */
const KEPT = new Set(["me", "wallet", "sessions", "templates"]);
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
      shouldDehydrateQuery: (query) =>
        query.state.status === "success" && KEPT.has(String(query.queryKey[0])),
    },
  });
  active = { uid, stop };
  await restored.catch(() => persister.removeClient());
}

/** Signing out forgets the figures, in memory and in the browser. */
export async function forget(): Promise<void> {
  active?.stop();
  active = null;
  await persister.removeClient();
}
