import { QueryClient, useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type Session = components["schemas"]["SessionResponse"];
export type Wallet = components["schemas"]["WalletResponse"];
export type HistoryEntry = components["schemas"]["HistoryEntry"];
export type Output = components["schemas"]["Output"];

/**
 * The cache rules (ADR 0018), in one place:
 * - Everything is fresh for 30 s, then refetched when a screen mounts or the window regains focus.
 * - While any session is live, the session list and the wallet poll every 5 s, and an open session every 3 s;
 *   once nothing is live, polling stops. Polling pauses while the tab is hidden.
 * - A 4xx is never retried (it won't change); anything else is retried twice, with backoff.
 * - Money is only ever what the server last returned: nothing is updated optimistically.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: true,
        refetchIntervalInBackground: false,
        retry: (failures, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
      },
    },
  });
}

export const LIVE_STATES = new Set([
  "REQUESTED",
  "MATCHED",
  "PROVISIONING",
  "RUNNING",
  "HOST_LOST",
  "FINISHING",
]);
export const isLive = (session: Pick<Session, "state">) => LIVE_STATES.has(session.state);

/**
 * Whether a session's figures can still change: until the API says it's settled (ended, its last settlement
 * posted and its hold released), it's watched.
 */
export const isChanging = (session: Pick<Session, "settled">) => !session.settled;

export const SESSIONS_POLL_MS = 5_000;
export const SESSION_POLL_MS = 3_000;

export const keys = {
  me: ["me"] as const,
  wallet: ["wallet"] as const,
  sessions: ["sessions"] as const,
  session: (id: string) => ["session", id] as const,
  outputs: (id: string) => ["outputs", id] as const,
  history: ["history"] as const,
};

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => data(api.GET("/v1/me")), staleTime: 5 * 60_000 });
}

export function useSessions() {
  return useQuery({
    queryKey: keys.sessions,
    queryFn: () => data(api.GET("/v1/sessions")),
    refetchInterval: (query) => (query.state.data?.sessions.some(isChanging) ? SESSIONS_POLL_MS : false),
  });
}

/** The wallet; `live` while a session's figures can change (isChanging), since holds and charges then change. */
export function useWallet(live = false) {
  return useQuery({
    queryKey: keys.wallet,
    queryFn: () => data(api.GET("/v1/wallet")),
    refetchInterval: live ? SESSIONS_POLL_MS : false,
  });
}

export function useSession(id: string | null) {
  return useQuery({
    queryKey: keys.session(id ?? ""),
    enabled: id !== null,
    queryFn: () => data(api.GET("/v1/sessions/{id}", { params: { path: { id: id! } } })),
    refetchInterval: (query) => (query.state.data && isChanging(query.state.data) ? SESSION_POLL_MS : false),
  });
}

/** A completed session's outputs; their links last 15 minutes, so they're refetched before that. */
export function useOutputs(id: string, completed: boolean) {
  return useQuery({
    queryKey: keys.outputs(id),
    enabled: completed,
    queryFn: () => data(api.GET("/v1/sessions/{id}/outputs", { params: { path: { id } } })),
    staleTime: 10 * 60_000,
    refetchInterval: 10 * 60_000,
  });
}

export const HISTORY_PAGE = 20;

export function useHistory() {
  return useInfiniteQuery({
    queryKey: keys.history,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/wallet/transactions", {
          params: { query: { limit: HISTORY_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? null,
  });
}

export function useTemplates() {
  return useQuery({
    queryKey: ["templates"],
    queryFn: () => data(api.GET("/v1/templates")),
    staleTime: 60 * 60_000,
  });
}

/** A template's name by id, or the id itself until the list arrives. */
export function useTemplateName(): (id: string) => string {
  const templates = useTemplates();
  return (id) => templates.data?.templates.find((template) => template.id === id)?.name ?? id;
}
