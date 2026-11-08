import { useQuery } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type ServiceStatus = components["schemas"]["PublicStatus"];

/** What's paused and whether IdleO is in maintenance (ADR 0056), for the frame's banner. */
export const STATUS_KEY = ["public-status"] as const;

/** Whether `error` is a refusal because part of IdleO is paused or in maintenance: the banner says what, at once. */
export function isPauseRefusal(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.type === "/problems/paused" || error.type === "/problems/maintenance")
  );
}

/**
 * The service's status, read after the first screen and every minute while the page is open (the server caches it
 * for 10 s), and at once whenever a request meets a pause (createQueryClient's error hook).
 */
export function useServiceStatus() {
  return useQuery({
    queryKey: STATUS_KEY,
    queryFn: () => data(api.GET("/v1/public/status")),
    staleTime: 10_000,
    refetchInterval: 60_000,
  });
}
