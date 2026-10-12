import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema.gen";

export type ApiKey = components["schemas"]["ApiKeyResponse"];
export type CreatedApiKey = components["schemas"]["CreatedApiKey"];
export type PendingCliLogin = components["schemas"]["PendingCliLogin"];

export const keyKeys = {
  list: ["api-keys"] as const,
  cliLogin: (code: string) => ["cli-login", code] as const,
};

/**
 * The person's API keys, newest first: never a secret, only the first characters of each (ADR 0025). `enabled` false
 * holds the first fetch back (useAfterPaint): the screen paints before they're asked for.
 */
export function useApiKeys(enabled = true) {
  return useQuery({ queryKey: keyKeys.list, enabled, queryFn: () => data(api.GET("/v1/api-keys")) });
}

/**
 * Makes a key. The answer holds the key itself, shown once: it goes to the screen that asked and nowhere else, never
 * the query cache (which is kept for the next visit), so the caller keeps it in its own state and `reset()`s this.
 * `idempotencyKey` belongs to the form as it stands, so a repeat after a lost answer is told so (`secret-shown-once`)
 * instead of making a second key.
 */
export function useCreateKey() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (variables: { name: string; scope: "read" | "full"; idempotencyKey: string }) =>
      data(
        api.POST("/v1/api-keys", {
          params: { header: { "Idempotency-Key": variables.idempotencyKey } },
          body: { name: variables.name, scope: variables.scope },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: keyKeys.list }),
  });
}

export function useRevokeKey() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { response, error } = await api.DELETE("/v1/api-keys/{id}", { params: { path: { id } } });
      if (!response.ok) {
        throw ApiError.of(response.status, error);
      }
    },
    onSuccess: () => client.invalidateQueries({ queryKey: keyKeys.list }),
  });
}

/** The CLI sign-in this code names, while it can be approved; a 404 once it's expired, used or never was. */
export function useCliLogin(code: string | null) {
  return useQuery({
    queryKey: keyKeys.cliLogin(code ?? ""),
    enabled: code !== null && code !== "",
    retry: false,
    queryFn: () => data(api.GET("/v1/cli-logins", { params: { query: { code: code ?? "" } } })),
  });
}

export function useApproveCliLogin() {
  return useMutation({
    mutationFn: (id: string) => data(api.POST("/v1/cli-logins/{id}/approve", { params: { path: { id } } })),
  });
}
