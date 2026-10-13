import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type SessionRating = components["schemas"]["SessionRating"];
export type Dispute = components["schemas"]["DisputeResponse"];

/**
 * A renter's ratings and reports (ADR 0028). The same rules as the rest of the cache (src/api/queries.ts): a 4xx is
 * never retried, and nothing is updated before the server has answered: a star shows as given only once the API has
 * returned the rating, and a refund appears only as the amount the API says.
 */
export const DISPUTES_PAGE = 20;

export const trustKeys = {
  rating: (sessionId: string) => ["rating", sessionId] as const,
  /** Every report of one session; `all` is the list on Account's Reports screen. */
  disputes: ["disputes"] as const,
  sessionDisputes: (sessionId: string) => ["disputes", "session", sessionId] as const,
};

/** How often a session's rating is asked for while the control plane hasn't noted that the session ended. */
export const UNRECORDED_POLL_MS = 3_000;

/** A session that ended longer ago than this and still isn't noted never will be: it ended before ratings existed. */
export const RECORDING_WINDOW_MS = 2 * 60_000;

/**
 * The session's rating and whether it can still be given or changed. `enabled` false holds the first read back. The
 * control plane notes a session's end a moment after the session changes, and says not-found until it has, so for the
 * first minutes after the end a not-found is asked again every few seconds (`endedAt`, from the session).
 */
export function useRating(sessionId: string, enabled = true, endedAt: string | null = null) {
  return useQuery({
    queryKey: trustKeys.rating(sessionId),
    enabled,
    queryFn: () => data(api.GET("/v1/sessions/{id}/rating", { params: { path: { id: sessionId } } })),
    refetchInterval: (query) =>
      query.state.error instanceof ApiError &&
      query.state.error.status === 404 &&
      isRecentEnd(endedAt, Date.now())
        ? UNRECORDED_POLL_MS
        : false,
  });
}

/** Whether a session ended recently enough for its end to be still on its way to being noted. */
export const isRecentEnd = (endedAt: string | null, now: number): boolean =>
  endedAt !== null && now - new Date(endedAt).getTime() < RECORDING_WINDOW_MS;

/** Gives the rating, or changes it; the cache then holds what the server answered. */
export function useRate(sessionId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (rating: { stars: number; comment: string }) =>
      data(
        api.POST("/v1/sessions/{id}/rating", {
          params: { path: { id: sessionId } },
          body: rating.comment === "" ? { stars: rating.stars } : rating,
        }),
      ),
    onSuccess: (saved) => client.setQueryData(trustKeys.rating(sessionId), saved),
  });
}

/** The reports of one session, newest first: the newest says where it stands. */
export function useSessionDisputes(sessionId: string, enabled = true) {
  return useQuery({
    queryKey: trustKeys.sessionDisputes(sessionId),
    enabled,
    queryFn: () =>
      data(api.GET("/v1/disputes", { params: { query: { session: sessionId, limit: DISPUTES_PAGE } } })),
  });
}

/** All of the renter's reports, newest first, a page at a time. */
export function useDisputePages(enabled = true) {
  return useInfiniteQuery({
    queryKey: [...trustKeys.disputes, "all"] as const,
    enabled,
    queryFn: ({ pageParam }) =>
      data(
        api.GET("/v1/disputes", {
          params: { query: { limit: DISPUTES_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
}

/**
 * Sends a report. `idempotencyKey` belongs to the form as it stands, so sending again after a lost answer returns the
 * same report instead of a second one.
 */
export function useReport() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (variables: { sessionId: string; reason: string; details: string; idempotencyKey: string }) =>
      data(
        api.POST("/v1/disputes", {
          params: { header: { "Idempotency-Key": variables.idempotencyKey } },
          body: {
            sessionId: variables.sessionId,
            reason: variables.reason,
            ...(variables.details === "" ? {} : { details: variables.details }),
          },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: trustKeys.disputes }),
  });
}
