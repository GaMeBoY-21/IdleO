import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import type { components } from "./schema.gen";

export type YourData = components["schemas"]["YourData"];
export type DataExport = components["schemas"]["Export"];

/** How often an export being made is asked about again, while the page is open (ADR 0037). */
export const EXPORT_POLL_MS = 5_000;

export const privacyKeys = {
  data: ["your-data"] as const,
  exports: ["exports"] as const,
};

/** What we hold about the person, by group, counts and dates only. */
export function useYourData(enabled = true) {
  return useQuery({ queryKey: privacyKeys.data, enabled, queryFn: () => data(api.GET("/v1/me/data")) });
}

/** The person's exports, newest first; asked again every few seconds while one is being made. */
export function useExports(enabled = true) {
  return useQuery({
    queryKey: privacyKeys.exports,
    enabled,
    queryFn: () => data(api.GET("/v1/me/exports")),
    refetchInterval: (query) =>
      query.state.data?.exports.some((item) => item.state === "requested") ? EXPORT_POLL_MS : false,
  });
}

/** Asks for an export of everything; the list then holds it, being made. */
export function useRequestExport() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => data(api.POST("/v1/me/exports")),
    onSuccess: () => client.invalidateQueries({ queryKey: privacyKeys.exports }),
  });
}

/** A short link to the person's ready export, asked for when they choose Download, never before. */
export function useDownloadExport() {
  return useMutation({
    mutationFn: (id: string) => data(api.GET("/v1/me/exports/{id}/download", { params: { path: { id } } })),
  });
}
