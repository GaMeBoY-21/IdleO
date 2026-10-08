import { ApiError, type ProblemType } from "@/api/errors";

/** A problem type's slug: `insufficient-credit` for `/problems/insufficient-credit`. */
export type ProblemSlug = ProblemType extends `/problems/${infer Slug}` ? Slug : never;

/**
 * What the renter reads for each problem type the API can return (docs/api/problems.md, ADR 0019), in
 * web/DESIGN.md's voice: what happened, then what to do. The type check fails if the API gains a type without words
 * here, and a test checks it against api/problems.json. Views branch on the type, never on the API's English.
 */
export const PROBLEM_MESSAGES: Record<ProblemSlug, string> = {
  "validation-failed": "Some details need fixing. Check the fields marked below.",
  "malformed-request": "That request couldn't be read. Reload the page and try again.",
  unauthenticated: "Your sign-in has expired. Sign in again to carry on.",
  "account-suspended":
    "Your account is suspended, so nothing can be booked. Contact us to have it reinstated.",
  "email-not-verified": "Your email address isn't verified yet. Open the link we sent you, then try again.",
  "access-denied": "This account can't do that.",
  "not-found": "We couldn't find that. It may have been removed, or the link is wrong.",
  "method-not-allowed": "That request isn't one we accept. Reload the page and try again.",
  "not-acceptable": "That request isn't one we accept. Reload the page and try again.",
  "unsupported-media-type": "That request isn't one we accept. Reload the page and try again.",
  "idempotency-key-invalid": "That request couldn't be sent safely. Reload the page and try again.",
  "request-in-progress": "We're still working on your last request. Wait a moment, then try again.",
  "idempotency-key-reused":
    "That repeats an earlier request with different details. Review it, then try again.",
  "version-required": "This changed since you opened it. Reload to see the latest, then try again.",
  "version-stale": "This changed since you opened it. Reload to see the latest, then try again.",
  "insufficient-credit":
    "You don't have enough credit for this booking's hold. Lower the most per hour or the spending cap.",
  "no-gpu-free": "No GPU is free right now. Try again in a minute; nothing was charged.",
  "session-not-cancellable": "This session has already started, so it can't be cancelled. Stop it instead.",
  "session-not-running": "This session isn't running any more, so there's nothing to stop.",
  "outputs-not-ready": "The outputs aren't ready yet. You can download them once the session completes.",
  "input-not-accepted":
    "This template doesn't take that kind of input. Choose the other kind, or leave it out.",
  "input-required": "This template needs an input. Upload a file or give a link to one.",
  "input-too-large": "That file is larger than this template takes. Choose a smaller one.",
  "upload-not-ready":
    "That upload isn't finished, or has expired. Wait for it to finish, or upload the file again.",
  "upload-parts-invalid": "The file didn't arrive complete. Upload it again.",
  "upload-in-use": "A session that hasn't ended uses this upload. You can delete it once the session ends.",
  "upload-quota-exceeded":
    "Your uploads are using all their space. Delete ones you no longer need, then try again.",
  "too-many-streams":
    "Too many sessions are open live in your other tabs. Close some; this page checks every few seconds meanwhile.",
  "internal-error": "Something went wrong on our side. Try again in a moment.",
};

/** The message for an error from an action: its problem type's words, or the network's. */
export function problemMessage(error: unknown): string {
  if (error instanceof ApiError && error.type !== null) {
    return PROBLEM_MESSAGES[error.type.slice("/problems/".length) as ProblemSlug];
  }
  if (error instanceof ApiError && error.status >= 500) {
    return PROBLEM_MESSAGES["internal-error"];
  }
  return "We couldn't reach IdleO. Check your connection and try again.";
}
