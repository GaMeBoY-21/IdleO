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
  "browser-sign-in-required": "Do this in the web app, signed in: an API key can't.",
  "api-key-read-only": "This API key can only read. Use a full key, or the web app.",
  "too-many-api-keys": "You have 20 API keys. Revoke one you no longer use, then create this one.",
  "invite-required": "Enter your invite code to get started.",
  "invite-invalid":
    "That code isn't one we can accept. Check it and try again, or ask the person who invited you.",
  "too-many-attempts": "Too many codes were tried. Wait an hour, then try again.",
  "notification-locked":
    "Messages about your money and your safety always stay on, so they can't be turned off.",
  "not-found": "We couldn't find that. It may have been removed, or the link is wrong.",
  "method-not-allowed": "That request isn't one we accept. Reload the page and try again.",
  "not-acceptable": "That request isn't one we accept. Reload the page and try again.",
  "unsupported-media-type": "That request isn't one we accept. Reload the page and try again.",
  "secret-shown-once":
    "That key was already created, and it's shown only once. Revoke it in the list, then create another.",
  "authorization-pending": "Waiting for you to approve the sign-in in the web app.",
  "cli-login-expired": "That sign-in link has expired or was already used. Run idleo login again.",
  "host-link-denied": "That link was denied. Start again from the app on your laptop.",
  "host-link-expired": "That link has expired. Start again from the app on your laptop.",
  "host-link-approved": "That laptop is already linked. To take it back, remove it from Hosting.",
  "rate-limited": "That's a lot of requests at once. Wait a moment, then try again.",
  "idempotency-key-invalid": "That request couldn't be sent safely. Reload the page and try again.",
  "request-in-progress": "We're still working on your last request. Wait a moment, then try again.",
  "idempotency-key-reused":
    "That repeats an earlier request with different details. Review it, then try again.",
  "version-required": "This changed since you opened it. Reload to see the latest, then try again.",
  "version-stale": "This changed since you opened it. Reload to see the latest, then try again.",
  "insufficient-credit": "You don't have enough credit for this booking's hold. Choose a lower budget.",
  "no-gpu-free": "No GPU is free right now. Try again in a minute; nothing was charged.",
  "session-not-cancellable": "This session has already started, so it can't be cancelled. Stop it instead.",
  "session-not-running": "This session isn't running any more, so there's nothing to stop.",
  "outputs-not-ready": "The outputs aren't ready yet. You can download them once the session completes.",
  "input-not-accepted":
    "This template doesn't take that kind of input. Choose the other kind, or leave it out.",
  "input-required":
    "This needs every input marked as needed. Upload it, pick it from My files, or give a link.",
  "input-too-large": "That's larger than this input takes. Choose a smaller one.",
  "upload-not-ready":
    "That upload isn't finished, or has expired. Wait for it to finish, or upload it again.",
  "upload-parts-invalid": "Your files didn't arrive complete. Upload them again.",
  "upload-in-use": "A session that hasn't ended uses this upload. You can delete it once the session ends.",
  "upload-quota-exceeded":
    "Your uploads are using all their space. Delete ones you no longer need in My files, then try again.",
  "too-many-new-sessions":
    "You've booked the most sessions allowed in an hour. Try again a little later; nothing was charged.",
  "too-many-sessions":
    "You have 3 sessions that haven't ended, the most at once. Wait for one to finish, or stop one.",
  "too-many-streams":
    "Too many sessions are open live in your other tabs. Close some; this page checks every few seconds meanwhile.",
  "host-busy":
    "This machine is running a session. Pause it so it takes no new work, and remove it when the session is over.",
  "host-removed": "This machine was removed. Add it again to rent it out.",
  "host-no-gpu": "This machine hasn't reported its GPU yet. Wait for it to connect, then try again.",
  "pause-too-long": "A pause can last 7 days at most. Choose a shorter one, or pause until you resume.",
  "session-not-rateable": "Only a session that ran for at least a minute can be rated.",
  "rating-closed": "It's too late to rate this session or change its rating: each closes after 7 days.",
  "report-window-closed": "Problems can be reported within 7 days of a session ending.",
  "report-already-open": "You've already reported this session. We'll reply within 2 days.",
  "report-already-made": "You've already reported this session, and we've replied. See Account, Reports.",
  "admin-code-invalid":
    "That code isn't right, or was already used. Five wrong codes in 15 minutes lock admin access.",
  "second-factor-exists": "Your authenticator is already set up. Sign in with a code from it.",
  "second-factor-missing": "Set up your authenticator first, then sign in with a code from it.",
  "action-refused": "That was refused, and nothing changed. The reason is shown below.",
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
