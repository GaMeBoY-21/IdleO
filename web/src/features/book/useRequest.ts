import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useMemo } from "react";
import { api, data } from "@/api/client";
import { ApiError } from "@/api/errors";
import { keys, writeSession, type Session } from "@/api/queries";
import type { CreateSession } from "./form";

/**
 * "Request a GPU": `POST /v1/sessions` with an Idempotency-Key (CLAUDE.md, ADR 0019). The key belongs to the request
 * as it stands: pressing again, or a retry after a network failure or a 5xx, sends the same key, so a flaky network
 * never books twice; changing anything makes a new one. A 4xx is the server's final answer and isn't retried.
 */
export function useRequest(request: CreateSession, onBooked: (session: Session) => void) {
  const client = useQueryClient();
  const body = JSON.stringify(request);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- a new key for each different request
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [body]);
  return useMutation({
    mutationFn: () =>
      data(
        api.POST("/v1/sessions", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request,
        }),
      ),
    retry: (failures, error) => failures < 2 && !(error instanceof ApiError && error.status < 500),
    onSuccess: (session) => {
      writeSession(client, session);
      void client.invalidateQueries({ queryKey: keys.sessions });
      void client.invalidateQueries({ queryKey: keys.wallet });
      onBooked(session);
    },
  });
}
