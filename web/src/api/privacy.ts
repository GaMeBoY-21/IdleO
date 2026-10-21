import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import createClient from "openapi-fetch";
import { config } from "@/config";
import { api, data } from "./client";
import type { components, paths } from "./schema.gen";

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

export type DeletionStanding = components["schemas"]["DeletionStanding"];

export const deletionKeys = { standing: ["deletion"] as const };

/** Where the person's deletion stands: their latest request, what stops it, what they'd lose, the wait. */
export function useDeletion(enabled = true) {
  return useQuery({
    queryKey: deletionKeys.standing,
    enabled,
    queryFn: () => data(api.GET("/v1/me/deletion")),
  });
}

/** Each change answers where it stands now, which is what the page then shows. */
function useDeletionChange<V>(change: (value: V) => Promise<DeletionStanding>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: change,
    onSuccess: (standing) => client.setQueryData(deletionKeys.standing, standing),
  });
}

/** Asks to delete the account: the confirmation link is emailed. Needs a sign-in in the last 10 minutes. */
export const useRequestDeletion = () => useDeletionChange(() => data(api.POST("/v1/me/deletion")));

/** Confirms with the emailed link's request and token: the wait begins. */
export const useConfirmDeletion = () =>
  useDeletionChange((link: { requestId: string; token: string }) =>
    data(api.POST("/v1/me/deletion/confirm", { body: link })),
  );

/** Cancels the waiting deletion, signed in. */
export const useCancelDeletion = () => useDeletionChange(() => data(api.DELETE("/v1/me/deletion")));

/** The cancel link from an email works signed out: its token is the proof, so no sign-in is asked for. */
const signedOut = createClient<paths>({ baseUrl: config.apiUrl });

export function useCancelByLink() {
  return useMutation({
    mutationFn: ({ requestId, token }: { requestId: string; token: string }) =>
      data(
        signedOut.POST("/v1/deletion-requests/{id}/cancel", {
          params: { path: { id: requestId } },
          body: { token },
        }),
      ),
  });
}
