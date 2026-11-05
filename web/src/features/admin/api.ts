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

/**
 * Asked when the server wants a recent factor (ADR 0051), by the step-up dialog the gate shows: resolves once one is
 * given, rejects if the admin declines.
 */
let askForStepUp: (() => Promise<void>) | null = null;

export function whenStepUpNeeded(ask: (() => Promise<void>) | null): void {
  askForStepUp = ask;
}

/**
 * The action on screen (ADR 0051): its idempotency key, the same for every try of it, and the version of the record it
 * was decided on. A POST made while one runs carries both.
 */
let action: { key: string; version: number | undefined; approval: AdminApproval | null } | null = null;

/**
 * Runs an admin's one action: `key` stays the same until it succeeds or the admin moves on. Answers with the approval
 * request it became, when it's of a kind another admin must approve; null when it's done.
 */
export async function asAction(
  key: string,
  record: { version: number } | undefined,
  run: () => Promise<unknown>,
): Promise<AdminApproval | null> {
  const current = { key, version: record?.version, approval: null };
  action = current;
  try {
    await run();
    return current.approval;
  } finally {
    action = null;
  }
}

/**
 * Every admin call. A POST carries an `Idempotency-Key`, and the record's version in `If-Match` when an action names one.
 * One refused with `step-up-required` asks for a factor in place and goes again, the very same request with the same key,
 * so the admin's action simply finishes.
 */
async function adminFetch(request: Request): Promise<Response> {
  if (request.method === "POST") {
    if (!request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", action?.key ?? crypto.randomUUID());
    }
    if (action?.version !== undefined && !request.headers.has("If-Match")) {
      request.headers.set("If-Match", String(action.version));
    }
  }
  const asked = action;
  const again = request.clone();
  const response = await apiFetch(request);
  if (response.status === 202 && asked !== null) {
    asked.approval = (await response.clone().json()) as AdminApproval;
  }
  if (response.status !== 403 || askForStepUp === null) {
    return response;
  }
  const problem = (await response
    .clone()
    .json()
    .catch(() => null)) as { type?: string } | null;
  if (problem?.type !== "/problems/step-up-required") {
    return response;
  }
  try {
    await askForStepUp();
  } catch {
    return response;
  }
  const retried = await apiFetch(again);
  if (retried.status === 202 && asked !== null) {
    asked.approval = (await retried.clone().json()) as AdminApproval;
  }
  return retried;
}

export const adminApi = createClient<paths>({ baseUrl: config.apiUrl, fetch: adminFetch });
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
export type AdminSearchHit = Schemas["SearchHit"];
export type AdminApproval = Schemas["AdminApproval"];
export type AdminAccount = Schemas["AdminAccount"];
export type AdminRole = Schemas["AdminRole"];
export type AdminPasskey = Schemas["AdminPasskey"];
export type AdminSessionRecord = Schemas["AdminSessionRecord"];
export type AdminDashboard = Schemas["AdminDashboard"];
export type AdminInstalls = Schemas["InstallsSummary"];
export type AdminUser = Schemas["AdminUser"];
export type AdminHost = Schemas["AdminHost"];
export type AdminScore = Schemas["AdminScore"];
export type AdminSelfTestStatus = Schemas["SelfTestStatus"];
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
export type AuditStanding = Schemas["AuditStanding"];
export type TicketStatus = Schemas["AdminAnswer"]["status"];
export type AbuseSummary = Schemas["AbuseSummary"];
export type AbuseDetail = Schemas["AbuseDetail"];
export type AdminPaymentMismatch = Schemas["AdminPaymentMismatch"];
export type AdminChargeback = Schemas["AdminChargeback"];
export type AdminCreditRefund = Schemas["AdminCreditRefund"];
export type AdminInvoice = Schemas["AdminInvoice"];
export type AdminHostFlag = Schemas["AdminHostFlag"];
export type BlocklistAddition = Schemas["BlocklistAddition"];
export type AdminPayout = Schemas["AdminPayout"];
export type AdminPayoutMismatch = Schemas["AdminPayoutMismatch"];
export type AdminPanReview = Schemas["AdminPanReview"];
export type AdminUpiReview = Schemas["AdminUpiReview"];
export type AdminClawback = Schemas["AdminClawback"];
export type AdminImage = Schemas["AdminImage"];
export type AdminImageDetail = Schemas["AdminImageDetail"];

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

/** Saves a file an admin call answered with, as its problem detail if it was refused. */
export async function adminFile(
  result: Promise<{ data?: unknown; error?: unknown; response: Response }>,
  name: string,
): Promise<void> {
  const { data: body, error, response } = await result;
  if (!response.ok || !(body instanceof Blob)) {
    throw ApiError.of(response.status, error);
  }
  const url = URL.createObjectURL(body);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
