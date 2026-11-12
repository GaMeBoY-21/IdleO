import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { ADMIN, adminApi, adminData } from "./api";

/**
 * Each record's own question, one for its page and for the links that open it: a link asks it when pointed at or
 * focused, so the record is usually there by the time it's opened (web/DESIGN.md "Navigation"). What was just asked
 * is fresh for a few seconds, so opening it doesn't ask again.
 */
const FRESH_MS = 5_000;

export const RECORDS = {
  host: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "host", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/hosts/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  user: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "user", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/users/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  session: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "session-record", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/sessions/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  dispute: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "dispute", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/disputes/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  chargeback: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "chargeback", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/chargebacks/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  ticket: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "ticket", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/tickets/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
  appeal: (id: string) =>
    queryOptions({
      queryKey: [ADMIN, "appeal", id],
      queryFn: () => adminData(adminApi.GET("/admin/v1/appeals/{id}", { params: { path: { id } } })),
      staleTime: FRESH_MS,
    }),
};

// What each record page's link asks for, by the page's address.
const ASK: Record<string, (client: QueryClient, id: string) => Promise<unknown>> = {
  "/admin/hosts/": (client, id) => client.query(RECORDS.host(id)),
  "/admin/users/": (client, id) => client.query(RECORDS.user(id)),
  "/admin/sessions/": (client, id) => client.query(RECORDS.session(id)),
  "/admin/disputes/": (client, id) => client.query(RECORDS.dispute(id)),
  "/admin/chargebacks/": (client, id) => client.query(RECORDS.chargeback(id)),
  "/admin/tickets/": (client, id) => client.query(RECORDS.ticket(id)),
  "/admin/appeals/": (client, id) => client.query(RECORDS.appeal(id)),
};

/** Asks for the record a link opens, if it opens one; a failure here is the record page's to show, not the link's. */
export function prefetchRecord(client: QueryClient, href: string): void {
  const url = new URL(href, window.location.origin);
  const ask = ASK[url.pathname];
  const id = url.searchParams.get("id");
  if (ask !== undefined && id) {
    ask(client, id).catch(() => undefined);
  }
}
