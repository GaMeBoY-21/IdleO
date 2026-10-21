# Generated from api/problems.json by scripts/generate.py. Do not edit; run it again after the contract changes.
"""One exception class for each problem type in the API's catalog (docs/api/problems.md)."""

from __future__ import annotations

from idleo._problem import IdleoError


class ValidationFailed(IdleoError):
    """The request has invalid fields (HTTP 400)."""

    slug = "validation-failed"
    status = 400
    title = "The request has invalid fields"


class MalformedRequest(IdleoError):
    """The request can't be read (HTTP 400)."""

    slug = "malformed-request"
    status = 400
    title = "The request can't be read"


class Unauthenticated(IdleoError):
    """Sign-in required (HTTP 401)."""

    slug = "unauthenticated"
    status = 401
    title = "Sign-in required"


class AccountSuspended(IdleoError):
    """Account suspended (HTTP 403)."""

    slug = "account-suspended"
    status = 403
    title = "Account suspended"


class EmailNotVerified(IdleoError):
    """Email not verified (HTTP 403)."""

    slug = "email-not-verified"
    status = 403
    title = "Email not verified"


class InviteRequired(IdleoError):
    """Invite code required (HTTP 403)."""

    slug = "invite-required"
    status = 403
    title = "Invite code required"


class InviteInvalid(IdleoError):
    """Invite code not accepted (HTTP 400)."""

    slug = "invite-invalid"
    status = 400
    title = "Invite code not accepted"


class NoticesChanged(IdleoError):
    """The Terms or privacy notice changed (HTTP 409)."""

    slug = "notices-changed"
    status = 409
    title = "The Terms or privacy notice changed"


class AgeNotConfirmed(IdleoError):
    """Age not confirmed (HTTP 422)."""

    slug = "age-not-confirmed"
    status = 422
    title = "Age not confirmed"


class FreshSignInRequired(IdleoError):
    """Sign in again first (HTTP 403)."""

    slug = "fresh-sign-in-required"
    status = 403
    title = "Sign in again first"


class ExportInProgress(IdleoError):
    """An export is being made (HTTP 409)."""

    slug = "export-in-progress"
    status = 409
    title = "An export is being made"


class ExportTooSoon(IdleoError):
    """One export a day (HTTP 429)."""

    slug = "export-too-soon"
    status = 429
    title = "One export a day"


class DeletionBlocked(IdleoError):
    """The account can't be deleted yet (HTTP 409)."""

    slug = "deletion-blocked"
    status = 409
    title = "The account can't be deleted yet"


class DeletionAlreadyRequested(IdleoError):
    """Deletion already asked for (HTTP 409)."""

    slug = "deletion-already-requested"
    status = 409
    title = "Deletion already asked for"


class DeletionLinkInvalid(IdleoError):
    """That link doesn't work (HTTP 400)."""

    slug = "deletion-link-invalid"
    status = 400
    title = "That link doesn't work"


class NoDeletionPending(IdleoError):
    """No deletion to cancel (HTTP 409)."""

    slug = "no-deletion-pending"
    status = 409
    title = "No deletion to cancel"


class MessageLooksAutomated(IdleoError):
    """That looked automated (HTTP 422)."""

    slug = "message-looks-automated"
    status = 422
    title = "That looked automated"


class TooManyAttempts(IdleoError):
    """Too many attempts (HTTP 429)."""

    slug = "too-many-attempts"
    status = 429
    title = "Too many attempts"


class BrowserSignInRequired(IdleoError):
    """Sign in with a browser for this (HTTP 403)."""

    slug = "browser-sign-in-required"
    status = 403
    title = "Sign in with a browser for this"


class ApiKeyReadOnly(IdleoError):
    """This key can only read (HTTP 403)."""

    slug = "api-key-read-only"
    status = 403
    title = "This key can only read"


class AccessDenied(IdleoError):
    """Access denied (HTTP 403)."""

    slug = "access-denied"
    status = 403
    title = "Access denied"


class NotFound(IdleoError):
    """Not found (HTTP 404)."""

    slug = "not-found"
    status = 404
    title = "Not found"


class MethodNotAllowed(IdleoError):
    """Method not allowed (HTTP 405)."""

    slug = "method-not-allowed"
    status = 405
    title = "Method not allowed"


class NotAcceptable(IdleoError):
    """Not acceptable (HTTP 406)."""

    slug = "not-acceptable"
    status = 406
    title = "Not acceptable"


class UnsupportedMediaType(IdleoError):
    """Unsupported media type (HTTP 415)."""

    slug = "unsupported-media-type"
    status = 415
    title = "Unsupported media type"


class NotificationLocked(IdleoError):
    """That notification can't be turned off (HTTP 422)."""

    slug = "notification-locked"
    status = 422
    title = "That notification can't be turned off"


class TooManyApiKeys(IdleoError):
    """Too many API keys (HTTP 409)."""

    slug = "too-many-api-keys"
    status = 409
    title = "Too many API keys"


class AuthorizationPending(IdleoError):
    """Waiting for approval (HTTP 400)."""

    slug = "authorization-pending"
    status = 400
    title = "Waiting for approval"


class HostLinkDenied(IdleoError):
    """Link denied (HTTP 400)."""

    slug = "host-link-denied"
    status = 400
    title = "Link denied"


class HostLinkExpired(IdleoError):
    """Link expired or used (HTTP 400)."""

    slug = "host-link-expired"
    status = 400
    title = "Link expired or used"


class HostLinkApproved(IdleoError):
    """Link already approved (HTTP 409)."""

    slug = "host-link-approved"
    status = 409
    title = "Link already approved"


class CliLoginExpired(IdleoError):
    """CLI sign-in expired or used (HTTP 400)."""

    slug = "cli-login-expired"
    status = 400
    title = "CLI sign-in expired or used"


class RateLimited(IdleoError):
    """Too many requests (HTTP 429)."""

    slug = "rate-limited"
    status = 429
    title = "Too many requests"


class IdempotencyKeyInvalid(IdleoError):
    """Idempotency-Key missing or invalid (HTTP 400)."""

    slug = "idempotency-key-invalid"
    status = 400
    title = "Idempotency-Key missing or invalid"


class RequestInProgress(IdleoError):
    """Request still in progress (HTTP 409)."""

    slug = "request-in-progress"
    status = 409
    title = "Request still in progress"


class SecretShownOnce(IdleoError):
    """Already created; its secret was shown once (HTTP 409)."""

    slug = "secret-shown-once"
    status = 409
    title = "Already created; its secret was shown once"


class IdempotencyKeyReused(IdleoError):
    """Idempotency-Key used for another request (HTTP 422)."""

    slug = "idempotency-key-reused"
    status = 422
    title = "Idempotency-Key used for another request"


class VersionRequired(IdleoError):
    """If-Match required (HTTP 428)."""

    slug = "version-required"
    status = 428
    title = "If-Match required"


class VersionStale(IdleoError):
    """Changed since it was read (HTTP 412)."""

    slug = "version-stale"
    status = 412
    title = "Changed since it was read"


class InsufficientCredit(IdleoError):
    """Not enough credit (HTTP 402)."""

    slug = "insufficient-credit"
    status = 402
    title = "Not enough credit"


class NoGpuFree(IdleoError):
    """No GPU is free (HTTP 409)."""

    slug = "no-gpu-free"
    status = 409
    title = "No GPU is free"


class SessionNotCancellable(IdleoError):
    """Session can't be cancelled (HTTP 409)."""

    slug = "session-not-cancellable"
    status = 409
    title = "Session can't be cancelled"


class SessionNotRunning(IdleoError):
    """Session isn't running (HTTP 409)."""

    slug = "session-not-running"
    status = 409
    title = "Session isn't running"


class OutputsNotReady(IdleoError):
    """Outputs not ready (HTTP 409)."""

    slug = "outputs-not-ready"
    status = 409
    title = "Outputs not ready"


class InputNotAccepted(IdleoError):
    """Template takes no such input (HTTP 400)."""

    slug = "input-not-accepted"
    status = 400
    title = "Template takes no such input"


class InputNotUsable(IdleoError):
    """That isn't what this input needs (HTTP 422)."""

    slug = "input-not-usable"
    status = 422
    title = "That isn't what this input needs"


class InputRequired(IdleoError):
    """Input required (HTTP 400)."""

    slug = "input-required"
    status = 400
    title = "Input required"


class InputTooLarge(IdleoError):
    """Input too large (HTTP 400)."""

    slug = "input-too-large"
    status = 400
    title = "Input too large"


class UploadNotReady(IdleoError):
    """Upload not ready (HTTP 409)."""

    slug = "upload-not-ready"
    status = 409
    title = "Upload not ready"


class UploadPartsInvalid(IdleoError):
    """Upload parts don't make the file (HTTP 400)."""

    slug = "upload-parts-invalid"
    status = 400
    title = "Upload parts don't make the file"


class UploadInUse(IdleoError):
    """Upload in use (HTTP 409)."""

    slug = "upload-in-use"
    status = 409
    title = "Upload in use"


class UploadQuotaExceeded(IdleoError):
    """Upload quota reached (HTTP 409)."""

    slug = "upload-quota-exceeded"
    status = 409
    title = "Upload quota reached"


class TooManyNewSessions(IdleoError):
    """Too many new sessions (HTTP 429)."""

    slug = "too-many-new-sessions"
    status = 429
    title = "Too many new sessions"


class TooManySessions(IdleoError):
    """Too many sessions at once (HTTP 409)."""

    slug = "too-many-sessions"
    status = 409
    title = "Too many sessions at once"


class TooManyStreams(IdleoError):
    """Too many open streams (HTTP 429)."""

    slug = "too-many-streams"
    status = 429
    title = "Too many open streams"


class HostBusy(IdleoError):
    """The host is running a session (HTTP 409)."""

    slug = "host-busy"
    status = 409
    title = "The host is running a session"


class HostRemoved(IdleoError):
    """The host was removed (HTTP 410)."""

    slug = "host-removed"
    status = 410
    title = "The host was removed"


class HostNoGpu(IdleoError):
    """The host hasn't reported a GPU (HTTP 409)."""

    slug = "host-no-gpu"
    status = 409
    title = "The host hasn't reported a GPU"


class PauseTooLong(IdleoError):
    """The pause is too long (HTTP 400)."""

    slug = "pause-too-long"
    status = 400
    title = "The pause is too long"


class NoAgentRelease(IdleoError):
    """No release yet (HTTP 404)."""

    slug = "no-agent-release"
    status = 404
    title = "No release yet"


class InstallEventsFull(IdleoError):
    """Too many events for this install (HTTP 422)."""

    slug = "install-events-full"
    status = 422
    title = "Too many events for this install"


class RequestTooLarge(IdleoError):
    """The request is too large (HTTP 413)."""

    slug = "request-too-large"
    status = 413
    title = "The request is too large"


class SessionNotRateable(IdleoError):
    """This session can't be rated (HTTP 409)."""

    slug = "session-not-rateable"
    status = 409
    title = "This session can't be rated"


class RatingClosed(IdleoError):
    """Too late to rate this session (HTTP 409)."""

    slug = "rating-closed"
    status = 409
    title = "Too late to rate this session"


class ReportWindowClosed(IdleoError):
    """Too late to report this session (HTTP 409)."""

    slug = "report-window-closed"
    status = 409
    title = "Too late to report this session"


class ReportAlreadyOpen(IdleoError):
    """This session already has an open report (HTTP 409)."""

    slug = "report-already-open"
    status = 409
    title = "This session already has an open report"


class ReportAlreadyMade(IdleoError):
    """This session has already been reported (HTTP 409)."""

    slug = "report-already-made"
    status = 409
    title = "This session has already been reported"


class AdminCodeInvalid(IdleoError):
    """Code not accepted (HTTP 400)."""

    slug = "admin-code-invalid"
    status = 400
    title = "Code not accepted"


class SecondFactorExists(IdleoError):
    """Second factor already set up (HTTP 409)."""

    slug = "second-factor-exists"
    status = 409
    title = "Second factor already set up"


class SecondFactorMissing(IdleoError):
    """Second factor not set up (HTTP 409)."""

    slug = "second-factor-missing"
    status = 409
    title = "Second factor not set up"


class ActionRefused(IdleoError):
    """Action refused (HTTP 409)."""

    slug = "action-refused"
    status = 409
    title = "Action refused"


class StorageUnavailable(IdleoError):
    """Storage unavailable (HTTP 503)."""

    slug = "storage-unavailable"
    status = 503
    title = "Storage unavailable"


class InternalError(IdleoError):
    """Internal error (HTTP 500)."""

    slug = "internal-error"
    status = 500
    title = "Internal error"


__all__ = [
    "ValidationFailed",
    "MalformedRequest",
    "Unauthenticated",
    "AccountSuspended",
    "EmailNotVerified",
    "InviteRequired",
    "InviteInvalid",
    "NoticesChanged",
    "AgeNotConfirmed",
    "FreshSignInRequired",
    "ExportInProgress",
    "ExportTooSoon",
    "DeletionBlocked",
    "DeletionAlreadyRequested",
    "DeletionLinkInvalid",
    "NoDeletionPending",
    "MessageLooksAutomated",
    "TooManyAttempts",
    "BrowserSignInRequired",
    "ApiKeyReadOnly",
    "AccessDenied",
    "NotFound",
    "MethodNotAllowed",
    "NotAcceptable",
    "UnsupportedMediaType",
    "NotificationLocked",
    "TooManyApiKeys",
    "AuthorizationPending",
    "HostLinkDenied",
    "HostLinkExpired",
    "HostLinkApproved",
    "CliLoginExpired",
    "RateLimited",
    "IdempotencyKeyInvalid",
    "RequestInProgress",
    "SecretShownOnce",
    "IdempotencyKeyReused",
    "VersionRequired",
    "VersionStale",
    "InsufficientCredit",
    "NoGpuFree",
    "SessionNotCancellable",
    "SessionNotRunning",
    "OutputsNotReady",
    "InputNotAccepted",
    "InputNotUsable",
    "InputRequired",
    "InputTooLarge",
    "UploadNotReady",
    "UploadPartsInvalid",
    "UploadInUse",
    "UploadQuotaExceeded",
    "TooManyNewSessions",
    "TooManySessions",
    "TooManyStreams",
    "HostBusy",
    "HostRemoved",
    "HostNoGpu",
    "PauseTooLong",
    "NoAgentRelease",
    "InstallEventsFull",
    "RequestTooLarge",
    "SessionNotRateable",
    "RatingClosed",
    "ReportWindowClosed",
    "ReportAlreadyOpen",
    "ReportAlreadyMade",
    "AdminCodeInvalid",
    "SecondFactorExists",
    "SecondFactorMissing",
    "ActionRefused",
    "StorageUnavailable",
    "InternalError",
]

PROBLEMS: dict[str, type[IdleoError]] = {
    "validation-failed": ValidationFailed,
    "malformed-request": MalformedRequest,
    "unauthenticated": Unauthenticated,
    "account-suspended": AccountSuspended,
    "email-not-verified": EmailNotVerified,
    "invite-required": InviteRequired,
    "invite-invalid": InviteInvalid,
    "notices-changed": NoticesChanged,
    "age-not-confirmed": AgeNotConfirmed,
    "fresh-sign-in-required": FreshSignInRequired,
    "export-in-progress": ExportInProgress,
    "export-too-soon": ExportTooSoon,
    "deletion-blocked": DeletionBlocked,
    "deletion-already-requested": DeletionAlreadyRequested,
    "deletion-link-invalid": DeletionLinkInvalid,
    "no-deletion-pending": NoDeletionPending,
    "message-looks-automated": MessageLooksAutomated,
    "too-many-attempts": TooManyAttempts,
    "browser-sign-in-required": BrowserSignInRequired,
    "api-key-read-only": ApiKeyReadOnly,
    "access-denied": AccessDenied,
    "not-found": NotFound,
    "method-not-allowed": MethodNotAllowed,
    "not-acceptable": NotAcceptable,
    "unsupported-media-type": UnsupportedMediaType,
    "notification-locked": NotificationLocked,
    "too-many-api-keys": TooManyApiKeys,
    "authorization-pending": AuthorizationPending,
    "host-link-denied": HostLinkDenied,
    "host-link-expired": HostLinkExpired,
    "host-link-approved": HostLinkApproved,
    "cli-login-expired": CliLoginExpired,
    "rate-limited": RateLimited,
    "idempotency-key-invalid": IdempotencyKeyInvalid,
    "request-in-progress": RequestInProgress,
    "secret-shown-once": SecretShownOnce,
    "idempotency-key-reused": IdempotencyKeyReused,
    "version-required": VersionRequired,
    "version-stale": VersionStale,
    "insufficient-credit": InsufficientCredit,
    "no-gpu-free": NoGpuFree,
    "session-not-cancellable": SessionNotCancellable,
    "session-not-running": SessionNotRunning,
    "outputs-not-ready": OutputsNotReady,
    "input-not-accepted": InputNotAccepted,
    "input-not-usable": InputNotUsable,
    "input-required": InputRequired,
    "input-too-large": InputTooLarge,
    "upload-not-ready": UploadNotReady,
    "upload-parts-invalid": UploadPartsInvalid,
    "upload-in-use": UploadInUse,
    "upload-quota-exceeded": UploadQuotaExceeded,
    "too-many-new-sessions": TooManyNewSessions,
    "too-many-sessions": TooManySessions,
    "too-many-streams": TooManyStreams,
    "host-busy": HostBusy,
    "host-removed": HostRemoved,
    "host-no-gpu": HostNoGpu,
    "pause-too-long": PauseTooLong,
    "no-agent-release": NoAgentRelease,
    "install-events-full": InstallEventsFull,
    "request-too-large": RequestTooLarge,
    "session-not-rateable": SessionNotRateable,
    "rating-closed": RatingClosed,
    "report-window-closed": ReportWindowClosed,
    "report-already-open": ReportAlreadyOpen,
    "report-already-made": ReportAlreadyMade,
    "admin-code-invalid": AdminCodeInvalid,
    "second-factor-exists": SecondFactorExists,
    "second-factor-missing": SecondFactorMissing,
    "action-refused": ActionRefused,
    "storage-unavailable": StorageUnavailable,
    "internal-error": InternalError,
}
