import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, data } from "./client";
import { ApiError } from "./errors";
import { keys } from "./queries";
import type { components } from "./schema.gen";

export type Me = components["schemas"]["MeResponse"];
export type NotificationItem = components["schemas"]["NotificationItem"];

export const notificationKeys = { choices: ["notifications"] as const };

/**
 * The person's notification choices, one entry for every kind, in the server's order. `enabled` false holds the
 * first fetch back (useAfterPaint): the screen paints before they're asked for.
 */
export function useNotifications(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.choices,
    enabled,
    queryFn: () => data(api.GET("/v1/me/notifications")),
  });
}

/**
 * Saves the whole set of choices (the server replaces them), and shows what the server answers: a switch moves
 * only once its save has been accepted, never before.
 */
export function useSaveNotifications() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (items: NotificationItem[]) =>
      data(
        api.PUT("/v1/me/notifications", {
          body: { items: items.map(({ kind, email, inApp }) => ({ kind, email, inApp })) },
        }),
      ),
    onSuccess: (saved) => client.setQueryData(notificationKeys.choices, saved),
  });
}

/** Changes the display name. The account's current version is read first, because the change must say which it's of. */
export function useRenameMe() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (displayName: string) => {
      const current = await api.GET("/v1/me");
      const etag = current.response.headers.get("ETag");
      if (!current.response.ok || etag === null) {
        throw ApiError.of(current.response.status, current.error);
      }
      return data(api.PATCH("/v1/me", { params: { header: { "If-Match": etag } }, body: { displayName } }));
    },
    onSuccess: (me) => client.setQueryData(keys.me, me),
  });
}

/** Spends an invite code; the activated account is what the server answers. */
export function useActivate() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => data(api.POST("/v1/me/activate", { body: { code } })),
    onSuccess: (me) => client.setQueryData(keys.me, me),
  });
}
