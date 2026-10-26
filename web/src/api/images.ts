import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type Image = components["schemas"]["ImageResponse"];
export type ImageList = components["schemas"]["ImageList"];
export type VulnerabilityList = components["schemas"]["VulnerabilityList"];

export const imageKeys = {
  list: ["images"] as const,
  one: (id: string) => ["image", id] as const,
  vulnerabilities: (id: string) => ["image", id, "vulnerabilities"] as const,
};

/** How often a check under way is asked about. */
export const CHECK_POLL_MS = 2_000;

/** Whether an image's check is still going. */
export const checking = (image: Pick<Image, "state">) =>
  image.state === "queued" || image.state === "checking";

/**
 * The renter's own images, newest first, and whether they may use their own images at all (ADR 0042). Read again while
 * any check is going.
 */
export function useImages(enabled = true) {
  return useQuery({
    queryKey: imageKeys.list,
    enabled,
    queryFn: () => data(api.GET("/v1/images")),
    refetchInterval: (query) => (query.state.data?.images.some(checking) ? CHECK_POLL_MS : false),
  });
}

/** One image and its check, asked about every 2 seconds until the check has ended. */
export function useImage(id: string | null) {
  return useQuery({
    queryKey: imageKeys.one(id ?? ""),
    enabled: id !== null && id !== "",
    queryFn: () => data(api.GET("/v1/images/{id}", { params: { path: { id: id ?? "" } } })),
    refetchInterval: (query) => (query.state.data && checking(query.state.data) ? CHECK_POLL_MS : false),
  });
}

/** Everything the check found in its packages, most severe first. */
export function useVulnerabilities(id: string, enabled: boolean) {
  return useQuery({
    queryKey: imageKeys.vulnerabilities(id),
    enabled,
    queryFn: () => data(api.GET("/v1/images/{id}/vulnerabilities", { params: { path: { id } } })),
  });
}

/**
 * Submits an image to be checked. A private registry's token goes in this one request and is never kept by the web app:
 * not in the query cache, not in storage. `idempotencyKey` belongs to the form as it stands, so a repeat after a lost
 * answer checks once.
 */
export function useSubmitImage() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (variables: {
      reference: string;
      registryUsername?: string;
      registryToken?: string;
      idempotencyKey: string;
    }) =>
      data(
        api.POST("/v1/images", {
          params: { header: { "Idempotency-Key": variables.idempotencyKey } },
          body: {
            reference: variables.reference,
            registryUsername: variables.registryUsername || null,
            registryToken: variables.registryToken || null,
          },
        }),
      ),
    onSuccess: (image) => {
      client.setQueryData(imageKeys.one(image.id), image);
      return client.invalidateQueries({ queryKey: imageKeys.list });
    },
  });
}

export function useRemoveImage() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { response, error } = await api.DELETE("/v1/images/{id}", { params: { path: { id } } });
      if (!response.ok) {
        throw ApiError.of(response.status, error);
      }
    },
    onSuccess: () => client.invalidateQueries({ queryKey: imageKeys.list }),
  });
}
