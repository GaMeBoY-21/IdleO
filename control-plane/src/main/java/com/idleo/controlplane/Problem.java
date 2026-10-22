package com.idleo.controlplane;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Every kind of error the API returns, in one place (ADR 0019, product surface 2.3). Each has a stable slug, its
 * HTTP status and a fixed title; a problem detail's {@code type} is the relative URI {@code /problems/<slug>} (RFC
 * 9457 section 3.1.1), so clients branch on it: the web app picks its message, the CLI its exit code and the SDK its
 * exception class. The {@code detail} says what happened this time. Thrown only through {@link ApiProblem#of}; {@code
 * docs/api/problems.md} is written from this list, and a test fails when the two differ.
 *
 * <p>Slugs never change once published. Another person's session or upload is {@link #NOT_FOUND}, never a 403, so
 * ids can't be probed.
 */
public enum Problem {

    // Requests and accounts.
    VALIDATION_FAILED(
            "validation-failed",
            HttpStatus.BAD_REQUEST,
            "The request has invalid fields",
            "A field is missing, has the wrong type or is out of range. `errors` names each field, for example "
                    + "`params.epochs`, and what's wrong with it."),
    MALFORMED_REQUEST(
            "malformed-request",
            HttpStatus.BAD_REQUEST,
            "The request can't be read",
            "The body isn't valid JSON, a required header is missing, or a path or query value has the wrong type."),
    UNAUTHENTICATED(
            "unauthenticated",
            HttpStatus.UNAUTHORIZED,
            "Sign-in required",
            "No bearer token was sent, or it's invalid or has expired. Sign in again and retry."),
    ACCOUNT_SUSPENDED(
            "account-suspended",
            HttpStatus.FORBIDDEN,
            "Account suspended",
            "The account is suspended, so nothing can be done with it until it's reinstated."),
    EMAIL_NOT_VERIFIED(
            "email-not-verified",
            HttpStatus.FORBIDDEN,
            "Email not verified",
            "The sign-in's email address isn't verified yet."),
    INVITE_REQUIRED(
            "invite-required",
            HttpStatus.FORBIDDEN,
            "Invite code required",
            "Access is by invitation. Until the account is activated with an invite code, only reading and "
                    + "changing the account itself and activating it are allowed."),
    INVITE_INVALID(
            "invite-invalid",
            HttpStatus.BAD_REQUEST,
            "Invite code not accepted",
            "The code isn't one that can be used: it's wrong, already used or expired. The response never says which."),
    TERMS_NOT_ACCEPTED(
            "terms-not-accepted",
            HttpStatus.FORBIDDEN,
            "Accept the Terms first",
            "Booking, uploading and adding or pricing a machine need the current Terms and privacy notice accepted, "
                    + "which only a person signed in in a browser can do. The detail says where. Reading, stopping, "
                    + "pausing, removing and downloading are never refused for it."),
    NOTICES_CHANGED(
            "notices-changed",
            HttpStatus.CONFLICT,
            "The Terms or privacy notice changed",
            "The versions accepted aren't the ones in force any more. Read GET /v1/me's `notices` and accept those."),
    AGE_NOT_CONFIRMED(
            "age-not-confirmed",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Age not confirmed",
            "An account is only for people of the minimum age in `notices.minimumAge`; confirm it with the first "
                    + "acceptance."),
    FRESH_SIGN_IN_REQUIRED(
            "fresh-sign-in-required",
            HttpStatus.FORBIDDEN,
            "Sign in again first",
            "This needs a sign-in in the last 10 minutes, in a browser. Sign out, sign in again, and retry."),
    EXPORT_IN_PROGRESS(
            "export-in-progress",
            HttpStatus.CONFLICT,
            "An export is being made",
            "Your last export isn't ready yet. One is made at a time; it's emailed when it is."),
    EXPORT_TOO_SOON(
            "export-too-soon",
            HttpStatus.TOO_MANY_REQUESTS,
            "One export a day",
            "An export can be asked for once a day. `Retry-After` says when the next one can be."),
    DELETION_BLOCKED(
            "deletion-blocked",
            HttpStatus.CONFLICT,
            "The account can't be deleted yet",
            "Something has to end first. `blockers` lists each, with what to do."),
    DELETION_ALREADY_REQUESTED(
            "deletion-already-requested",
            HttpStatus.CONFLICT,
            "Deletion already asked for",
            "This account's deletion is already waiting for its email confirmation, or scheduled."),
    DELETION_LINK_INVALID(
            "deletion-link-invalid",
            HttpStatus.BAD_REQUEST,
            "That link doesn't work",
            "The link isn't one that can be used: it's wrong, used already or expired. The response never says which."),
    NO_DELETION_PENDING(
            "no-deletion-pending",
            HttpStatus.CONFLICT,
            "No deletion to cancel",
            "The account isn't waiting to be deleted, or its erasure has already begun."),
    MESSAGE_LOOKS_AUTOMATED(
            "message-looks-automated",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "That looked automated",
            "The message was sent faster than a person writes, or a field people don't see was filled. Wait a moment, "
                    + "then send it again."),
    TOO_MANY_ATTEMPTS(
            "too-many-attempts",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many attempts",
            "Too many invite codes were tried in the last hour. Wait, then try again."),
    BROWSER_SIGN_IN_REQUIRED(
            "browser-sign-in-required",
            HttpStatus.FORBIDDEN,
            "Sign in with a browser for this",
            "API keys can't manage keys, approve a CLI sign-in, add, remove or price a machine, issue its enrolment "
                    + "code or change notification choices. Do it in the web app, signed in."),
    API_KEY_READ_ONLY(
            "api-key-read-only",
            HttpStatus.FORBIDDEN,
            "This key can only read",
            "A read key can only make GET requests (and revoke itself). Use a full key to change anything."),
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "Access denied", "The caller may not do this."),
    NOT_FOUND(
            "not-found",
            HttpStatus.NOT_FOUND,
            "Not found",
            "There's nothing at this address, or it belongs to someone else."),
    METHOD_NOT_ALLOWED(
            "method-not-allowed",
            HttpStatus.METHOD_NOT_ALLOWED,
            "Method not allowed",
            "This address doesn't take that HTTP method."),
    NOT_ACCEPTABLE(
            "not-acceptable",
            HttpStatus.NOT_ACCEPTABLE,
            "Not acceptable",
            "The response can't be given in any media type the request's Accept header allows."),
    UNSUPPORTED_MEDIA_TYPE(
            "unsupported-media-type",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported media type",
            "The body's Content-Type isn't one this address takes; send application/json."),

    NOTIFICATION_LOCKED(
            "notification-locked",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "That notification can't be turned off",
            "Messages about money and safety are always on, by email and in the app: stopped for credit or the "
                    + "spending cap, a host lost, a machine offline, payouts and top-ups, and a new device signing in."),

    TOO_MANY_API_KEYS(
            "too-many-api-keys",
            HttpStatus.CONFLICT,
            "Too many API keys",
            "A person can have at most 20 keys that aren't revoked. Revoke one you no longer use first."),

    AUTHORIZATION_PENDING(
            "authorization-pending",
            HttpStatus.BAD_REQUEST,
            "Waiting for approval",
            "The CLI sign-in or host link hasn't been approved in the web app yet. Keep polling at the interval it "
                    + "gave (RFC 8628's authorization_pending)."),
    HOST_LINK_DENIED(
            "host-link-denied",
            HttpStatus.BAD_REQUEST,
            "Link denied",
            "The person who opened the link said it isn't their laptop, so it was denied. The agent should stop polling; "
                    + "link again from the app on the laptop if it was a mistake."),
    HOST_LINK_EXPIRED(
            "host-link-expired",
            HttpStatus.BAD_REQUEST,
            "Link expired or used",
            "The link has expired (links last 10 minutes), or its enrolment code was already handed out. Link again "
                    + "from the app on the laptop."),
    HOST_LINK_APPROVED(
            "host-link-approved",
            HttpStatus.CONFLICT,
            "Link already approved",
            "This laptop was already linked, so the link can't be denied. To take it back, remove it from Hosting."),
    CLI_LOGIN_EXPIRED(
            "cli-login-expired",
            HttpStatus.BAD_REQUEST,
            "CLI sign-in expired or used",
            "The CLI sign-in has expired, or its key was already handed out. Start a new one with idleo login "
                    + "(RFC 8628's expired_token)."),

    RATE_LIMITED(
            "rate-limited",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests",
            "More requests than the limit allows: 60 a minute for each person, counted once for a session's event "
                    + "stream and its logs. Retry-After says how many seconds to wait."),

    // Idempotency and versions.
    IDEMPOTENCY_KEY_INVALID(
            "idempotency-key-invalid",
            HttpStatus.BAD_REQUEST,
            "Idempotency-Key missing or invalid",
            "A create request needs an Idempotency-Key header: 1 to 255 visible ASCII characters, without spaces."),
    REQUEST_IN_PROGRESS(
            "request-in-progress",
            HttpStatus.CONFLICT,
            "Request still in progress",
            "A request with this Idempotency-Key is still being processed. Retry shortly with the same key."),
    SECRET_SHOWN_ONCE(
            "secret-shown-once",
            HttpStatus.CONFLICT,
            "Already created; its secret was shown once",
            "A request with this Idempotency-Key already created it, and its secret was shown only in that first "
                    + "answer. Revoke it (the detail names it) and create another."),
    IDEMPOTENCY_KEY_REUSED(
            "idempotency-key-reused",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Idempotency-Key used for another request",
            "This Idempotency-Key was already used with a different request body. Use a new key for a new request."),
    VERSION_REQUIRED(
            "version-required",
            HttpStatus.PRECONDITION_REQUIRED,
            "If-Match required",
            "A change needs If-Match with the ETag from the last read of the resource."),
    VERSION_STALE(
            "version-stale",
            HttpStatus.PRECONDITION_FAILED,
            "Changed since it was read",
            "The resource changed since the ETag in If-Match was read. Read it again and retry."),

    // Money and sessions.
    INSUFFICIENT_CREDIT(
            "insufficient-credit",
            HttpStatus.PAYMENT_REQUIRED,
            "Not enough credit",
            "The booking's hold (30 minutes at the maximum price, up to the maximum spend) is more than the available "
                    + "credit."),
    NO_GPU_FREE(
            "no-gpu-free",
            HttpStatus.CONFLICT,
            "No GPU is free",
            "The booking left out its price ceiling, so the server uses the highest price among the GPUs that fit "
                    + "right now, and none fits right now. Nothing was created; try again shortly, or set a ceiling."),
    SESSION_NOT_CANCELLABLE(
            "session-not-cancellable",
            HttpStatus.CONFLICT,
            "Session can't be cancelled",
            "Only a session that hasn't run (REQUESTED, MATCHED or PROVISIONING) can be cancelled; stop a running one."),
    SESSION_NOT_RUNNING(
            "session-not-running",
            HttpStatus.CONFLICT,
            "Session isn't running",
            "Only a RUNNING session can be stopped: cancel one that hasn't started; one that has ended can't be "
                    + "stopped."),
    OUTPUTS_NOT_READY(
            "outputs-not-ready",
            HttpStatus.CONFLICT,
            "Outputs not ready",
            "Outputs can be downloaded once the session is COMPLETED."),

    // Inputs, uploads and streams.
    INPUT_NOT_ACCEPTED(
            "input-not-accepted",
            HttpStatus.BAD_REQUEST,
            "Template takes no such input",
            "The input doesn't take this kind: an upload where it takes only a link, or a link where it takes only "
                    + "an upload."),
    INPUT_NOT_USABLE(
            "input-not-usable",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "That isn't what this input needs",
            "The upload is ready, but its files aren't what the template needs for this input (a folder without "
                    + "config.json for a model, a file of the wrong kind), or a zip that can't be read. The detail says "
                    + "what to upload instead."),
    INPUT_REQUIRED(
            "input-required",
            HttpStatus.BAD_REQUEST,
            "Input required",
            "An input the template needs is missing: give it an upload or a link."),
    INPUT_TOO_LARGE(
            "input-too-large", HttpStatus.BAD_REQUEST, "Input too large", "The upload is larger than the input takes."),
    UPLOAD_NOT_READY(
            "upload-not-ready",
            HttpStatus.CONFLICT,
            "Upload not ready",
            "The upload can't be used as an input until it's ready, and can't be completed or get fresh part links "
                    + "once it's no longer pending: it may have failed, expired or been deleted."),
    UPLOAD_PARTS_INVALID(
            "upload-parts-invalid",
            HttpStatus.BAD_REQUEST,
            "Upload parts don't make the file",
            "A part is missing or its ETag doesn't match what storage holds, or the finished file isn't the size the "
                    + "upload declared; a wrong size fails the upload."),
    UPLOAD_IN_USE(
            "upload-in-use",
            HttpStatus.CONFLICT,
            "Upload in use",
            "A session that hasn't ended uses this upload as an input; delete it once the session ends."),
    UPLOAD_QUOTA_EXCEEDED(
            "upload-quota-exceeded",
            HttpStatus.CONFLICT,
            "Upload quota reached",
            "Pending and ready uploads together would be more than the per-renter quota; delete some first."),
    TOO_MANY_NEW_SESSIONS(
            "too-many-new-sessions",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many new sessions",
            "More sessions booked in the last hour than the limit: 10, or 2 for an account less than a day old. "
                    + "Retry-After says when the next one may be booked."),
    TOO_MANY_SESSIONS(
            "too-many-sessions",
            HttpStatus.CONFLICT,
            "Too many sessions at once",
            "A person may have at most 3 sessions that haven't ended. Wait for one to finish, or stop one."),
    TOO_MANY_STREAMS(
            "too-many-streams",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many open streams",
            "This renter already has the most event streams open at once; close one first."),

    // Hosts.
    HOST_BUSY(
            "host-busy",
            HttpStatus.CONFLICT,
            "The host is running a session",
            "A host can't be removed while it has a session that hasn't ended. Pause it so it takes no new work, "
                    + "and remove it when the session is over."),
    HOST_REMOVED(
            "host-removed",
            HttpStatus.GONE,
            "The host was removed",
            "This host was removed and never comes back; add the machine again to rent it out."),
    HOST_NO_GPU(
            "host-no-gpu",
            HttpStatus.CONFLICT,
            "The host hasn't reported a GPU",
            "A price can only be suggested once the host's agent has connected and reported its GPU."),
    PAUSE_TOO_LONG(
            "pause-too-long",
            HttpStatus.BAD_REQUEST,
            "The pause is too long",
            "A pause with an end must end within 7 days from now. Pause until you resume it for longer."),

    // The Windows app (ADR 0034).
    NO_AGENT_RELEASE(
            "no-agent-release",
            HttpStatus.NOT_FOUND,
            "No release yet",
            "The Windows app hasn't been released yet, so there's nothing to download. Try again later."),
    INSTALL_EVENTS_FULL(
            "install-events-full",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Too many events for this install",
            "One install can send at most 200 events in all. Nothing from this request was kept."),
    REQUEST_TOO_LARGE(
            "request-too-large",
            HttpStatus.CONTENT_TOO_LARGE,
            "The request is too large",
            "The body is bigger than this call accepts. Send less at once."),

    // Ratings and reports (ADR 0028).
    SESSION_NOT_RATEABLE(
            "session-not-rateable",
            HttpStatus.CONFLICT,
            "This session can't be rated",
            "Only a session that ran for at least a minute can be rated."),
    RATING_CLOSED(
            "rating-closed",
            HttpStatus.CONFLICT,
            "Too late to rate this session",
            "A session can be rated within 7 days of ending, and its rating changed within 7 days of being given."),
    REPORT_WINDOW_CLOSED(
            "report-window-closed",
            HttpStatus.CONFLICT,
            "Too late to report this session",
            "A problem with a session can be reported within 7 days of it ending."),
    REPORT_ALREADY_OPEN(
            "report-already-open",
            HttpStatus.CONFLICT,
            "This session already has an open report",
            "A session has one report, and this one is open: we'll reply to it."),
    REPORT_ALREADY_MADE(
            "report-already-made",
            HttpStatus.CONFLICT,
            "This session has already been reported",
            "A session can be reported once. Its report was resolved, and what we decided is in Account, Reports."),

    // The admin console (ADR 0027).
    ADMIN_CODE_INVALID(
            "admin-code-invalid",
            HttpStatus.BAD_REQUEST,
            "Code not accepted",
            "The authenticator code or recovery code is wrong, or was already used. The fifth wrong code in 15 "
                    + "minutes locks admin access until an operator grants it again."),
    SECOND_FACTOR_EXISTS(
            "second-factor-exists",
            HttpStatus.CONFLICT,
            "Second factor already set up",
            "The admin's authenticator is already set up, and its secret is never shown again. Sign in with a code "
                    + "from it, or a recovery code."),
    SECOND_FACTOR_MISSING(
            "second-factor-missing",
            HttpStatus.CONFLICT,
            "Second factor not set up",
            "Set up the authenticator first: ask for a secret, then confirm it with a code from the app."),
    ACTION_REFUSED(
            "action-refused",
            HttpStatus.CONFLICT,
            "Action refused",
            "An admin action was refused and nothing changed. The detail says why: a host without a valid "
                    + "certificate, say, or a reversal the wallet's available credit can't cover."),

    // Server.
    STORAGE_UNAVAILABLE(
            "storage-unavailable",
            HttpStatus.SERVICE_UNAVAILABLE,
            "Storage unavailable",
            "Our file storage can't be reached just now, so uploads, outputs and logs can't be read or written. "
                    + "Sessions keep running. Retry-After says when to try again."),
    INTERNAL_ERROR(
            "internal-error",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal error",
            "Something failed on our side. Nothing about it is in the response; it's logged for us.");

    /** Where {@code type} points: {@code /problems/<slug>}, relative so the domain can be chosen later. */
    public static final String TYPE_PREFIX = "/problems/";

    private final String slug;
    private final HttpStatus status;
    private final String title;
    private final String when;

    Problem(String slug, HttpStatus status, String title, String when) {
        this.slug = slug;
        this.status = status;
        this.title = title;
        this.when = when;
    }

    public String slug() {
        return slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** When it happens, for the published list. */
    public String when() {
        return when;
    }

    public URI type() {
        return URI.create(TYPE_PREFIX + slug);
    }
}
