import { createAsyncStoragePersister } from "@tanstack/query-async-storage-persister";
import { persistQueryClient, type PersistedClient } from "@tanstack/query-persist-client-core";
import { hashKey, type QueryClient } from "@tanstack/react-query";
import { del, get, set } from "idb-keyval";
import { KEPT_ME_KEY, keptMe } from "./kept-me";
import type { components } from "./schema.gen";

/**
 * What the renter saw last time, shown at once when they come back, then refreshed (web/DESIGN.md principles 3
 * and 5). Only these are kept: the home's (what the frame draws of the user, the wallet, the session list and the template names) and an
 * owner's (their machines, each machine, their earnings; ADR 0023), so the Hosting tab's figure and a machine's
 * name don't wait on the network for their first paint, which a cold start would otherwise make a second slower
 * than the budget. A machine's telemetry, price band and sessions are never kept. The
 * cache is kept in this browser's IndexedDB for the signed-in user only: it's restored for the person it was kept for
 * (keptUser) while sign-in, which comes after the paint, is still loading, and goes as soon as sign-in says that's someone
 * else or no one. A different user's cache is thrown away, it's dropped after a day, and signing out removes it.
 * Restored figures always show when they're from until fresh ones arrive, and they're never taken as fresh: however
 * recent, they're refetched once the screen has shown them (refreshRestored), so the network never holds it back.
 */
const KEPT = new Set(["me", KEPT_ME_KEY[0], "wallet", "sessions", "templates", "hosts", "host", "earnings"]);

/** A kept query: one of KEPT's, but not what hangs off a machine (its telemetry, price band and sessions). */
const isKept = (key: readonly unknown[]) =>
  KEPT.has(String(key[0])) && (key[0] !== "host" || key.length === 2);
const MAX_AGE_MS = 24 * 60 * 60_000;

/** The account never goes into the browser whole: only what a first paint draws of it, under a key of its own (kept-me.ts). */
function withKeptMe(client: PersistedClient): PersistedClient {
  const { queries } = client.clientState;
  const me = queries.find((query) => query.queryKey[0] === "me");
  if (!me) {
    return client;
  }
  const others = queries.filter((query) => query !== me && query.queryKey[0] !== KEPT_ME_KEY[0]);
  const kept = {
    ...me,
    queryKey: KEPT_ME_KEY,
    queryHash: hashKey(KEPT_ME_KEY),
    state: { ...me.state, data: keptMe(me.state.data as components["schemas"]["MeResponse"]) },
  };
  return { ...client, clientState: { ...client.clientState, queries: [...others, kept] } };
}

const persister = createAsyncStoragePersister({
  key: "idleo.home-cache",
  serialize: (client) => JSON.stringify(withKeptMe(client)),
  storage: {
    getItem: (key) => get<string>(key).then((value) => value ?? null),
    setItem: set,
    removeItem: del,
  },
  throttleTime: 1_000,
});

let kept: Promise<string | null> | null = null;

/** Whom the figures in this browser were kept for, if they're still good: who was signed in here last. Read once per page. */
export function keptUser(): Promise<string | null> {
  kept ??= Promise.resolve(persister.restoreClient())
    .then((client) => (client && Date.now() - client.timestamp <= MAX_AGE_MS ? client.buster || null : null))
    .catch(() => null);
  return kept;
}

let active: { uid: string; stop: () => void } | null = null;
let unrefreshed = false;
/** When the restore finished: what was fetched after it is newer than anything restored. */
let restoredAt = 0;

/** Restores the signed-in user's last figures into the client, and keeps saving them; safe to call again. */
export async function restoreFor(client: QueryClient, uid: string): Promise<void> {
  if (active?.uid === uid) {
    return;
  }
  if (active) {
    // Restored for whoever was here last, and sign-in says it's someone else: nothing of theirs stays on screen.
    active.stop();
    client.clear();
  }
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
  restoredAt = Date.now();
  unrefreshed = true;
}

/**
 * Marks what restoreFor brought back as stale, refetching what's on screen; once per restore. Only what came from the
 * restore: a figure this page has fetched since is already fresh, and refetching it put a second copy of the screen's
 * own requests on the network just after its first paint (the Hosting tab's LCP, 2.4 s instead of 1.95 s, whenever its
 * data had arrived before the paint).
 */
export function refreshRestored(client: QueryClient): void {
  if (!unrefreshed) {
    return;
  }
  unrefreshed = false;
  void client.invalidateQueries({
    predicate: (query) => isKept(query.queryKey) && query.state.dataUpdatedAt < restoredAt,
  });
}

/** Signing out forgets the figures, in memory and in the browser. */
export async function forget(): Promise<void> {
  active?.stop();
  active = null;
  unrefreshed = false;
  restoredAt = 0;
  await persister.removeClient();
}
