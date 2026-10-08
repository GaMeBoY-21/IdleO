import type { UseQueryResult } from "@tanstack/react-query";
import type { Session, Wallet } from "@/api/queries";
import * as data from "../../e2e/fixtures";

/** A query result in a given state, for rendering a view without a server. */
export function queryResult<T>(state: Partial<UseQueryResult<T>>): UseQueryResult<T> {
  return {
    data: undefined,
    error: null,
    isPending: false,
    isError: false,
    isRefetchError: false,
    isFetching: false,
    dataUpdatedAt: data.NOW.getTime(),
    refetch: async () => ({}) as never,
    ...state,
  } as UseQueryResult<T>;
}

/** The unit tests' data comes from the same builder as every screen's (e2e/fixtures.ts). */
export const wallet: Wallet = data.asha.wallet;

export function session(overrides: Partial<Session> = {}): Session {
  return { ...data.running, ...overrides };
}

export { data };
