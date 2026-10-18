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
export type HostLink = components["schemas"]["LinkResponse"];
export type LatestRelease = components["schemas"]["LatestRelease"];

/**
 * The owner's side of the cache (ADR 0023): their machines, what each is doing, what they have earned. The same rules
 * as the renter's (src/api/queries.ts): fresh for 30 s, a 4xx never retried, nothing about money updated
 * optimistically. While a machine runs a session its figures are read again every 5 seconds, so what it has earned so
 * far changes as the server says; the elapsed time is the app's clock.
 */
export const HOSTS_POLL_MS = 5_000;
export const HOST_SESSIONS_PAGE = 20;
export const EARNINGS_PAGE = 20;

export const hostKeys = {
  hosts: ["hosts"] as const,
  host: (id: string) => ["host", id] as const,
  telemetry: (id: string) => ["host", id, "telemetry"] as const,
  priceBand: (id: string) => ["host", id, "price-band"] as const,
  sessions: (id: string) => ["host", id, "sessions"] as const,
  earnings: ["earnings"] as const,
  link: (id: string) => ["host-link", id] as const,
  gpuBand: (model: string, vramMb: number) => ["price-band", model, vramMb] as const,
  release: ["agent-release", "latest"] as const,
};

/**
 * The Windows app's latest release (ADR 0034), for Add a machine's Download button; `null` when there's none yet
 * (`no-agent-release`), which is an answer, not an error. The API lets it be cached for a minute; so does this.
 */
export function useLatestRelease() {
  return useQuery({
    queryKey: hostKeys.release,
    staleTime: 60_000,
    queryFn: async (): Promise<LatestRelease | null> => {
      try {
        return await data(api.GET("/v1/agent-releases/latest"));
      } catch (error) {
        if (error instanceof ApiError && error.type === "/problems/no-agent-release") {
          return null;
        }
        throw error;
      }
    },
  });
}

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
 * The link a laptop asked for (ADR 0032), as the approval page reads it. It is asked for again while it's pending, so
 * the page notices when it runs out; never cached for long, since a link lasts ten minutes.
 */
export function useHostLink(id: string) {
  return useQuery({
    queryKey: hostKeys.link(id),
    queryFn: () => data(api.GET("/v1/host-links/{id}", { params: { path: { id } } })),
    staleTime: 0,
    // A link that isn't there (a wrong address) is a 404, shown as such, never retried.
    retry: (failures, error) =>
      !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
  });
}

/** Approve the link: the name and the listing the laptop starts with, in one request. The host is made pending. */
export function useApproveHostLink(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: components["schemas"]["ApproveLinkRequest"]) =>
      data(api.POST("/v1/host-links/{id}/approve", { params: { path: { id } }, body })),
    retry: noRetryOn4xx,
    onSuccess: (link) => {
      client.setQueryData(hostKeys.link(id), link);
      void client.invalidateQueries({ queryKey: hostKeys.hosts });
    },
  });
}

/** "This isn't my laptop". */
export function useDenyHostLink(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => data(api.POST("/v1/host-links/{id}/deny", { params: { path: { id } } })),
    retry: noRetryOn4xx,
    onSuccess: (link) => client.setQueryData(hostKeys.link(id), link),
  });
}

/** The suggested price range for a GPU that isn't a machine yet: what the approval page prefills its price from. */
export function useGpuPriceBand(gpu: { model: string; vramMb: number } | undefined) {
  return useQuery({
    queryKey: hostKeys.gpuBand(gpu?.model ?? "", gpu?.vramMb ?? 0),
    enabled: gpu !== undefined,
    queryFn: () =>
      data(
        api.GET("/v1/price-band", {
          params: { query: { gpuModel: gpu!.model, vramMb: gpu!.vramMb } },
        }),
      ),
    retry: (failures, error) =>
      !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failures < 2,
  });
}
