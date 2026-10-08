import {
  QueryClient,
  useInfiniteQuery,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type Session = components["schemas"]["SessionResponse"];
export type Wallet = components["schemas"]["WalletResponse"];
export type HistoryEntry = components["schemas"]["HistoryEntry"];
export type Output = components["schemas"]["Output"];
export type SessionList = components["schemas"]["SessionList"];
export type Template = components["schemas"]["TemplateResponse"];

/**
 * The cache rules (ADR 0018), in one place:
 * - Everything is fresh for 30 s, then refetched when a screen mounts or the window regains focus.
 * - While any session in view can still change, the session lists and the wallet poll every 5 s; once every one
 *   is settled, polling stops. Polling pauses while the tab is hidden.
 * - An open session follows its event stream (src/api/useSessionStream.ts), which writes each snapshot here
 *   (writeSession); it polls every 5 s only while the stream is down.
 * - A 4xx is never retried (it won't change); anything else is retried twice, with backoff.
 * - Money is only ever what the server last returned: nothing is updated optimistically.
 */
export function createQueryClient(): QueryClient {
  const client = new QueryClient({
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
  // A log can hold 150,000 lines: comparing each new copy with the last, as the cache does by default, would cost
  // more than drawing it. Its lines are only ever added to, so the new copy is taken as it is.
  client.setQueryDefaults(["logs"], { structuralSharing: false });
  return client;
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
/** How often an open session is read while its stream is down. */
export const SESSION_POLL_MS = 5_000;

export const keys = {
  me: ["me"] as const,
  wallet: ["wallet"] as const,
  /** Home's list, the newest page; every list of sessions starts with this key. */
  sessions: ["sessions"] as const,
  sessionsList: (active: boolean) => ["sessions", "list", active] as const,
  session: (id: string) => ["session", id] as const,
  logs: (id: string) => ["logs", id] as const,
  outputs: (id: string) => ["outputs", id] as const,
  history: ["history"] as const,
  availability: ["availability"] as const,
};

type CachedList = SessionList | InfiniteData<SessionList>;

const pagesOf = (list: CachedList): SessionList[] => ("pages" in list ? list.pages : [list]);

/**
 * The one way a session's newer state enters the cache: its own entry, and its row in every list that holds it,
 * so the session screen, Home and All sessions show the same figures. Nothing else keeps session state.
 */
export function writeSession(client: QueryClient, session: Session): void {
  client.setQueryData(keys.session(session.id), session);
  client.setQueriesData<CachedList>({ queryKey: keys.sessions }, (list) => {
    if (!list || !pagesOf(list).some((page) => page.sessions.some((s) => s.id === session.id))) {
      return list;
    }
    const replace = (page: SessionList): SessionList => ({
      ...page,
      sessions: page.sessions.map((s) => (s.id === session.id ? session : s)),
    });
    return "pages" in list ? { ...list, pages: list.pages.map(replace) } : replace(list);
  });
}

/** A session as a list last showed it, so opening it from a list shows it at once (web/DESIGN.md principle 5). */
function fromLists(client: QueryClient, id: string): { session: Session; at: number } | undefined {
  for (const query of client.getQueryCache().findAll({ queryKey: keys.sessions })) {
    const list = query.state.data as CachedList | undefined;
    const session =
      list &&
      pagesOf(list)
        .flatMap((page) => page.sessions)
        .find((s) => s.id === id);
    if (session) {
      return { session, at: query.state.dataUpdatedAt };
    }
  }
  return undefined;
}

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

export const SESSIONS_PAGE = 20;

/** All sessions, active or ended, newest first, a page at a time; polled while any in view can still change. */
export function useSessionPages(active: boolean) {
  return useInfiniteQuery({
    queryKey: keys.sessionsList(active),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/sessions", {
          params: { query: { active, limit: SESSIONS_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? null,
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.sessions.some(isChanging)) ? SESSIONS_POLL_MS : false,
  });
}

/**
 * The wallet; `live` while a session's figures can change (isChanging), since holds and charges then change.
 * `enabled` false holds the first fetch back (useAfterPaint), showing what's cached meanwhile.
 */
export function useWallet(live = false, enabled = true) {
  return useQuery({
    queryKey: keys.wallet,
    enabled,
    queryFn: () => data(api.GET("/v1/wallet")),
    refetchInterval: live ? SESSIONS_POLL_MS : false,
  });
}

/** One session; `poll` while its event stream is down, so its figures keep moving. */
export function useSession(id: string | null, poll = false) {
  const client = useQueryClient();
  return useQuery({
    queryKey: keys.session(id ?? ""),
    enabled: id !== null,
    queryFn: () => data(api.GET("/v1/sessions/{id}", { params: { path: { id: id! } } })),
    initialData: () => (id === null ? undefined : fromLists(client, id)?.session),
    initialDataUpdatedAt: () => (id === null ? undefined : fromLists(client, id)?.at),
    refetchInterval: (query) =>
      poll && query.state.data && isChanging(query.state.data) ? SESSION_POLL_MS : false,
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

/** What's free now, by GPU (ADR 0019): counts and prices, never a host. */
export function useAvailability(enabled = true) {
  return useQuery({ queryKey: keys.availability, enabled, queryFn: () => data(api.GET("/v1/availability")) });
}

export function useTemplates() {
  return useQuery({
    queryKey: ["templates"],
    queryFn: () => data(api.GET("/v1/templates")),
    staleTime: 60 * 60_000,
  });
}

/**
 * Whether template names are known (or can't be, after an error). Names wrap rather than being cut off, so a
 * screen that shows them waits for them, or a name arriving would change a row's height.
 */
export function useTemplatesKnown(): boolean {
  const templates = useTemplates();
  return templates.data !== undefined || templates.isError;
}

/** A template's name by id, or the id itself until the list arrives. */
export function useTemplateName(): (id: string) => string {
  const templates = useTemplates();
  return (id) => templates.data?.templates.find((template) => template.id === id)?.name ?? id;
}
