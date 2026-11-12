import * as credit from "./fixtures-credit";
import * as data from "./fixtures";
import * as images from "./fixtures-images";
import * as publicData from "./fixtures-public";
import * as support from "./fixtures-support";
import * as trust from "./fixtures-trust";
import { hostingApi } from "./hosting-api";
import { snapshot, type Answer } from "./support";

/**
 * A whole site's API answered from fixtures, for the specs that open every page of a site rather than one screen's
 * states: "full" (e2e/full-screens.spec.ts) and the UI review's sweep (e2e/ui-review.spec.ts). A screen's own spec keeps
 * its own, narrower answers.
 */
export type QuoteBody = Parameters<typeof data.quoteAnswer>[0];

/** The running session, which the renter app's session screen opens. */
export const RUNNING =
  data.asha.sessions.find((session) => session.state === "RUNNING") ?? data.asha.sessions[0]!;

/** Every request the renter app's pages make, signed in as the fixtures' renter. */
export const renterApi = (): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/wallet/transactions": { json: data.history(0) },
  "/v1/wallet/billing-details": { json: credit.billingDetails },
  "/v1/wallet/invoices": { json: credit.invoices },
  "/v1/wallet/refund-requests": { json: credit.noRefunds },
  "/v1/sessions/[^/]+/events": snapshot(RUNNING),
  "/v1/sessions/[^/]+/logs": {
    json: { chunks: data.logChunks(RUNNING), nextCursor: "after-6", complete: false },
  },
  "/v1/sessions/[^/]+/outputs": { json: data.outputs },
  "/v1/sessions/[^/]+/rating": { json: trust.ratingOpen(RUNNING) },
  "/v1/sessions/[^/]+": { json: RUNNING },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  "/v1/disputes": { json: trust.disputesPage() },
  "/v1/uploads/[^/]+": { json: data.sessionUpload },
  "/v1/uploads": { json: data.uploadsPage() },
  "/v1/availability": { json: data.availability },
  "/v1/session-quotes": (_call, request) => data.quoteAnswer(request.postDataJSON() as QuoteBody),
  "/v1/usage": { json: data.usageOf(data.asha.sessions) },
  "/v1/public/availability": { json: publicData.availability() },
  "/v1/public/contact": { json: support.contact() },
  "/v1/api-keys": { json: data.apiKeys() },
  "/v1/cli-logins": { json: data.pendingCliLogin() },
  "/v1/images": { json: images.imageList() },
  "/v1/notifications": { json: data.inbox(3) },
  "/v1/support/tickets/[^/]+": { json: support.thread() },
  "/v1/support/tickets": { json: support.tickets() },
});

/** The host site's: the renter's account and the hosting screens (ADR 0023, ADR 0040). */
export const hostApi = (): Record<string, Answer> => ({ ...renterApi(), ...hostingApi() });

/** The website's live figures (ADR 0029). */
export const websiteApi = (): Record<string, Answer> => ({
  "/v1/public/availability": { json: publicData.availability(10_000) },
  "/v1/public/contact": { json: support.contact() },
});
