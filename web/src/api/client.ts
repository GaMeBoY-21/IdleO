import createClient from "openapi-fetch";
import { authStore } from "@/auth";
import { config } from "@/config";
import { authedFetch } from "./authed-fetch";
import { ApiError } from "./errors";
import type { paths } from "./schema.gen";

/**
 * The typed API client (ADR 0018). Its types are generated from api/openapi.yaml on every build, typecheck and
 * test, so an API change that breaks the web app fails its type check.
 */
export const api = createClient<paths>({
  baseUrl: config.apiUrl,
  fetch: authedFetch({
    getIdToken: (forceRefresh) => authStore.getIdToken(forceRefresh),
    signOut: (reason) => authStore.signOut(reason),
  }),
});

/** The data of a response, or its problem detail as an ApiError. */
export async function data<T>(
  result: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data: body, error, response } = await result;
  if (!response.ok || body === undefined) {
    throw ApiError.of(response.status, error);
  }
  return body;
}
