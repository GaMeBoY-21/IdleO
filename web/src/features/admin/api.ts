import createClient from "openapi-fetch";
import { apiFetch, data } from "@/api/client";
import { ApiError } from "@/api/errors";
import type { components, paths } from "@/api/schema.gen";
import { config } from "@/config";

/**
 * The admin console's API (ADR 0027): the same typed client as the app's, with the caller's ID token, plus the admin
 * session's token in the `Admin-Session` header. That token lives only in this module's memory: never in storage,
 * never in the query cache, so a reload asks for a code again.
 */
let token: string | null = null;
const listeners = new Set<() => void>();

export const adminSession = {
  token: (): string | null => token,
  set: (value: string) => {
    token = value;
    listeners.forEach((listener) => listener());
  },
  clear: () => {
    token = null;
    listeners.forEach((listener) => listener());
  },
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

export const adminApi = createClient<paths>({ baseUrl: config.apiUrl, fetch: apiFetch });
adminApi.use({
  onRequest({ request }) {
    if (token !== null) {
      request.headers.set("Admin-Session", token);
    }
    return request;
  },
});

/** Every admin query's key starts with this, so signing out of the console forgets all of them at once. */
export const ADMIN = "admin";

type Schemas = components["schemas"];
export type AdminSessionStatus = Schemas["AdminSessionStatus"];
export type AdminDashboard = Schemas["AdminDashboard"];
export type AdminUser = Schemas["AdminUser"];
export type AdminHost = Schemas["AdminHost"];
export type AdminHostSummary = Schemas["AdminHostSummary"];
export type AdminSession = Schemas["AdminSession"];
export type AdminSessionSummary = Schemas["AdminSessionSummary"];
export type AdminReviewMinute = Schemas["AdminReviewMinute"];
export type AdminDispute = Schemas["AdminDispute"];
export type AdminDisputeSummary = Schemas["AdminDisputeSummary"];
export type AdminUsage = Schemas["AdminUsage"];
export type AdminUsageMinute = Schemas["AdminUsageMinute"];
export type AdminCreditGrant = Schemas["AdminCreditGrant"];
export type AdminLedgerEntry = Schemas["AdminLedgerEntry"];
export type AdminReconciliationRun = Schemas["AdminReconciliationRun"];
export type AdminInvite = Schemas["AdminInvite"];
export type AuditEntry = Schemas["AuditEntry"];

let onNotFound: () => void = () => undefined;

/** Called with every admin 404, set by the gate: the admin session may have lapsed, so where the admin stands is asked again. */
export function whenNotFound(callback: () => void): void {
  onNotFound = callback;
}

/**
 * An admin call's data, or its problem as an ApiError. A 404 may be a record that isn't there, or an admin session
 * that has lapsed (the server answers both alike), so the gate asks again where the admin stands.
 */
export async function adminData<T>(
  result: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  try {
    return await data(result);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      onNotFound();
    }
    throw error;
  }
}
