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
            "The template takes no input, or not of this kind (an upload or a URL)."),
    INPUT_REQUIRED(
            "input-required",
            HttpStatus.BAD_REQUEST,
            "Input required",
            "The template needs an input: an upload or a URL."),
    INPUT_TOO_LARGE(
            "input-too-large",
            HttpStatus.BAD_REQUEST,
            "Input too large",
            "The upload is larger than the template's largest input."),
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
            "A session that hasn't ended uses this upload as its input; delete it once the session ends."),
    UPLOAD_QUOTA_EXCEEDED(
            "upload-quota-exceeded",
            HttpStatus.CONFLICT,
            "Upload quota reached",
            "Pending and ready uploads together would be more than the per-renter quota; delete some first."),
    TOO_MANY_STREAMS(
            "too-many-streams",
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many open streams",
            "This renter already has the most event streams open at once; close one first."),

    // Server.
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
