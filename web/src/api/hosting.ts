import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type Host = components["schemas"]["HostResponse"];
export type HostList = components["schemas"]["HostList"];
export type Listing = components["schemas"]["Listing"];
export type Telemetry = components["schemas"]["Telemetry"];
export type PriceBand = components["schemas"]["PriceBand"];
export type HostSession = components["schemas"]["HostSession"];
export type OwnerEarnings = components["schemas"]["OwnerEarnings"];
export type EarningsEntry = components["schemas"]["EarningsEntry"];
export type EnrolmentCode = components["schemas"]["EnrolmentCodeResponse"];

/**
 * The owner's side of the cache (ADR 0023): their machines, what each is doing, what they have earned. The same rules
 * as the renter's (src/api/queries.ts): fresh for 30 s, a 4xx never retried, nothing about money updated
 * optimistically. While a machine runs a session its figures are read again every 5 seconds, so what it has earned so
 * far changes as the server says; the elapsed time is the app's clock.
 */
export const HOSTS_POLL_MS = 5_000;
/** How often the add-a-machine screen asks whether the agent has connected. */
export const CONNECT_POLL_MS = 3_000;
export const HOST_SESSIONS_PAGE = 20;
export const EARNINGS_PAGE = 20;

export const hostKeys = {
  hosts: ["hosts"] as const,
  host: (id: string) => ["host", id] as const,
  telemetry: (id: string) => ["host", id, "telemetry"] as const,
  priceBand: (id: string) => ["host", id, "price-band"] as const,
  sessions: (id: string) => ["host", id, "sessions"] as const,
  earnings: ["earnings"] as const,
};

const running = (host: Host) => host.currentSession != null;

/** Every machine the person owns, not the removed ones; read again every 5 s while one of them runs a session. */
export function useHosts(enabled = true) {
  return useQuery({
    queryKey: hostKeys.hosts,
    enabled,
    queryFn: () => data(api.GET("/v1/hosts")),
    refetchInterval: (query) => (query.state.data?.hosts.some(running) ? HOSTS_POLL_MS : false),
  });
}

/**
 * One machine. It opens from the list's copy, so it paints at once. It's read again while it runs a session, and, when
 * `poll` says so, every `poll.ms` while `poll.until` hasn't happened: the add screen waits for its agent this way.
 */
export function useHost(id: string | null, poll?: { ms: number; until: (host: Host) => boolean }) {
  const client = useQueryClient();
  return useQuery({
    queryKey: hostKeys.host(id ?? ""),
    enabled: id !== null,
    queryFn: () => data(api.GET("/v1/hosts/{id}", { params: { path: { id: id! } } })),
    initialData: () => fromList(client, id),
    initialDataUpdatedAt: () => client.getQueryState(hostKeys.hosts)?.dataUpdatedAt,
    refetchInterval: (query) => {
      const host = query.state.data;
      if (poll && (!host || !poll.until(host))) {
        return poll.ms;
      }
      return host && running(host) ? HOSTS_POLL_MS : false;
    },
  });
}

function fromList(client: QueryClient, id: string | null): Host | undefined {
  return id === null
    ? undefined
    : client.getQueryData<HostList>(hostKeys.hosts)?.hosts.find((host) => host.id === id);
}

/** The latest GPU readings and the last hour (for the owner's screen only), read again every 5 s while it's open. */
export function useTelemetry(id: string, enabled = true) {
  return useQuery({
    queryKey: hostKeys.telemetry(id),
    enabled,
    queryFn: () => data(api.GET("/v1/hosts/{id}/telemetry", { params: { path: { id } } })),
    refetchInterval: HOSTS_POLL_MS,
    staleTime: HOSTS_POLL_MS,
  });
}

/** The suggested price range for a machine: other hosts with its GPU, or a default. */
export function usePriceBand(id: string, enabled = true) {
  return useQuery({
    queryKey: hostKeys.priceBand(id),
    enabled,
    queryFn: () => data(api.GET("/v1/hosts/{hostId}/price-band", { params: { path: { hostId: id } } })),
    // A machine with no GPU reported yet has no band: the screen says so, without retrying.
    retry: (failures, error) =>
      !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
  });
}

/** The sessions a machine ran, newest first, a page at a time. */
export function useHostSessions(id: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: hostKeys.sessions(id),
    enabled,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/hosts/{hostId}/sessions", {
          params: {
            path: { hostId: id },
            query: { limit: HOST_SESSIONS_PAGE, ...(pageParam ? { cursor: pageParam } : {}) },
          },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? null,
  });
}

/**
 * Earnings across every machine: the totals come with each page, the history a page at a time. They're read again
 * every 5 s while a machine runs a session, since a settlement lands in them.
 */
export function useEarnings(live = false, enabled = true) {
  return useInfiniteQuery({
    queryKey: hostKeys.earnings,
    enabled,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/earnings", {
          params: { query: { limit: EARNINGS_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.history.nextCursor ?? null,
    refetchInterval: live ? HOSTS_POLL_MS : false,
  });
}

/** A machine the server has just returned: into the cache, its row in the list, and the screens that read it. */
export function writeHost(client: QueryClient, host: Host): void {
  client.setQueryData(hostKeys.host(host.id), host);
  client.setQueryData<HostList>(hostKeys.hosts, (list) =>
    list && list.hosts.some((h) => h.id === host.id)
      ? { hosts: list.hosts.map((h) => (h.id === host.id ? host : h)) }
      : list,
  );
}

const noRetryOn4xx = (failures: number, error: unknown) =>
  failures < 2 && !(error instanceof ApiError && error.status < 500);

/** Pause a machine until a time, or until its owner resumes it. */
export function usePause(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (until: string | null) =>
      data(api.POST("/v1/hosts/{id}/pause", { params: { path: { id } }, body: { until } })),
    onSuccess: (host) => writeHost(client, host),
  });
}

export function useResume(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => data(api.POST("/v1/hosts/{id}/resume", { params: { path: { id } } })),
    onSuccess: (host) => writeHost(client, host),
  });
}

export function useRename(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (name: string) =>
      data(api.PATCH("/v1/hosts/{id}", { params: { path: { id } }, body: { name } })),
    onSuccess: (host) => {
      writeHost(client, host);
      void client.invalidateQueries({ queryKey: hostKeys.earnings });
    },
  });
}

/** Remove a machine for good: it leaves the list, and its earnings stay in the total. */
export function useRemove(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const { response, error } = await api.DELETE("/v1/hosts/{id}", { params: { path: { id } } });
      if (!response.ok) {
        throw ApiError.of(response.status, error);
      }
    },
    onSuccess: () => {
      client.removeQueries({ queryKey: hostKeys.host(id) });
      void client.invalidateQueries({ queryKey: hostKeys.hosts });
      void client.invalidateQueries({ queryKey: hostKeys.earnings });
    },
  });
}

/** Save a machine's price and limits; the quote's range and the price band move with it. */
export function useSaveListing(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (listing: components["schemas"]["ListingRequest"]) =>
      data(api.PUT("/v1/hosts/{id}/listing", { params: { path: { id } }, body: listing })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: hostKeys.host(id) });
      void client.invalidateQueries({ queryKey: hostKeys.hosts });
      void client.invalidateQueries({ queryKey: ["availability"] });
    },
  });
}

/**
 * Add a machine: `POST /v1/hosts` with an Idempotency-Key (CLAUDE.md), so pressing again or retrying after a network
 * failure never makes a second one. The caller keeps the key for as long as the name is the same.
 */
export function useAddMachine() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ name, idempotencyKey }: { name: string; idempotencyKey: string }) =>
      data(
        api.POST("/v1/hosts", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: { name },
        }),
      ),
    retry: noRetryOn4xx,
    onSuccess: (host) => {
      client.setQueryData(hostKeys.host(host.id), host);
      void client.invalidateQueries({ queryKey: hostKeys.hosts });
    },
  });
}

/** A new one-time code for a machine: shown once, never stored, valid for an hour. */
export function useEnrolmentCode() {
  return useMutation({
    mutationFn: (id: string) => data(api.POST("/v1/hosts/{id}/enrolment-code", { params: { path: { id } } })),
  });
}
