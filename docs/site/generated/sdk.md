IdleO's Python SDK: run, follow and download sessions from a script or a notebook. It works like the command line: the same sign-in, the same uploads that carry on after a failure, and the same meanings for what goes wrong.

## Install and sign in

From a copy of the repository: `pip install ./sdk/python` (or `uv add ./sdk/python`). It needs Python 3.10 or newer.

`Client()` finds your API key in this order: the `api_key` argument, the `IDLEO_API_KEY` environment variable, then the key `idleo login` stored on this computer. So after `idleo login` there is nothing to set. The API's address is the `api_url` argument, else `IDLEO_API_URL`.

## Run a session

```python
from idleo import Client

client = Client()
s = client.sessions.run(
    "fine-tune-simulated",
    inputs={"model": "./my-model", "data": "./train.jsonl"},
    presets={"thoroughness": "standard"},
    max_spend_paise=200_00,
)
for line in s.logs(follow=True):
    print(line)
s.wait()
s.outputs.download("./out")
```

You bring the model and the data; we provide the GPU. Each input is a folder or file on your computer (uploaded first, with its paths kept), an https link the job fetches itself, or the id of an upload you made before. The names of a service's inputs, presets and parameters are in the services page, or `client.templates.list()`.

Money is whole paise everywhere: `max_spend_paise=200_00` is ₹200. `idleo.rupees(paise)` writes an amount for people.

In a Jupyter notebook, `wait()`, `logs(follow=True)` and uploads show their progress as a line that updates in place; anywhere else they are silent unless you pass `progress=True`.

## `Client`

Your account's door to the API.

The API key is `api_key`, else the `IDLEO_API_KEY` environment variable, else the one `idleo login` stored on this
computer (the keychain, or the credentials file in the config folder). The API's address is `api_url`, else
`IDLEO_API_URL`, else the local stack. Without a key every call raises NotSignedIn.

### `client.me() -> MeResponse`

Who you're signed in as.

### `client.credit() -> WalletResponse`

Your balance, what's held for running sessions, and what's available; all in paise.

### `client.availability() -> Availability`

Which GPU memory classes have a host online now, and their price.

### `client.close() -> None`

Closes the connections; a `with Client() as client:` block does it for you.

## `client.templates`

The services you can run, from `GET /v1/templates`: the one list the web form and the command line's flags are
made from.

### `client.templates.list() -> list[TemplateResponse]`

Every template you can book, with its inputs, parameters and questions.

### `client.templates.get(template_id: str) -> TemplateResponse`

One template by its id. Raises UsageError, naming the ones there are, for an id there isn't.

## `client.uploads`

Files and folders on their way to storage; the same upload the command line and the web app make.

### `client.uploads.upload(path: str | Path, *, label: str | None = None, progress: bool | None = None) -> uuid.UUID`

Uploads a folder (every file, paths kept) or one file, straight to storage, and returns the upload's id,
for `inputs=`. Called again with the same files after a failure, it carries on from the last part storage
took.

## `client.sessions`

Booking, finding and following sessions.

### `client.sessions.run(template: str, *, inputs: Mapping[str, str | Path | UUID] | None = None, params: Mapping[str, Any] | None = None, presets: Mapping[str, str] | None = None, gpu_memory_gb: int | None = None, max_spend_paise: int | None = None, max_runtime: int | timedelta | None = None, progress: bool | None = None, max_spend: int | None = None) -> Session`

Uploads the inputs and books the session; it doesn't wait for it (use `wait()` and `logs()`).

`inputs` maps each of the template's input names to a folder or file on this computer (uploaded first, paths
kept, carried on if a first try was cut off), an https:// link the job fetches itself, or the id of an upload
you made before. `max_spend_paise` is the most the session may cost, in paise (`200_00` is ₹200); the
template's usual budget if left out. `max_runtime` is seconds or a timedelta. `progress` shows the upload as it
goes: by default only in a notebook. `max_spend` is the old name of `max_spend_paise`, kept for one release.

### `client.sessions.create(template: str, *, inputs: Mapping[str, str | Path | UUID] | None = None, params: Mapping[str, Any] | None = None, presets: Mapping[str, str] | None = None, gpu_memory_gb: int | None = None, max_spend_paise: int | None = None, max_runtime: int | timedelta | None = None, max_spend: int | None = None) -> Session`

Books a session without uploading anything: each input is an https:// link or the id of an upload made
with `client.uploads.upload()`. It doesn't follow the session either. The arguments are `run()`'s.

### `client.sessions.get(session_id: str | uuid.UUID) -> Session`

One of your sessions by its id.

### `client.sessions.list(*, active: bool | None = None, limit: int = 20) -> list[Session]`

Your newest sessions, up to `limit`.

### `client.sessions.iter(*, active: bool | None = None) -> Iterator[Session]`

Your sessions, newest first, a page at a time as you read them. `active` keeps only those still going.

## `Session`

A booked session. `data` is the API's whole answer as of the last refresh; `refresh()` asks again.

### `session.id`

The session's id.

### `session.state`

Where it is: REQUESTED, RUNNING, COMPLETED, FAILED, CANCELLED, NO_HOST_FREE and the states between.

### `session.settled`

True once nothing will change any more: the charge is final.

### `session.charged_paise`

What has been charged, in paise; final once `settled`.

### `session.cost_paise`

What it has cost so far: what's been charged, and what has run since but isn't settled yet.

### `session.max_spend_paise`

The most it may cost, in paise: the spending cap it was booked with.

### `session.error_code`

Why it ended badly, when it did (a code the API names); None otherwise.

### `session.gpu`

The GPU it runs on (model and memory), once one is assigned.

### `session.started_at`

When it began running (UTC), if it has.

### `session.ended_at`

When it stopped running (UTC), if it has.

### `session.refresh() -> Session`

Asks the API for the session as it is now, and returns it.

### `session.events() -> Iterator[Event]`

The live view: the whole session each time it changes, and a note when a new log chunk exists. A dropped
connection is picked up again; it ends once the session is settled.

### `session.wait(timeout: float | None = None, *, progress: bool | None = None, check: bool = True) -> Session`

Waits until the session is settled and returns it, with its final figures.

`progress` shows its state and cost as it goes: by default only in a notebook. With `check` (the default) a
session that didn't complete raises: NoGpuFree if no GPU became free for it, else SessionNotCompleted, which
carries the session. Raises WaitTimeout if `timeout` seconds pass first; the session keeps going.

### `session.logs(follow: bool = False, *, progress: bool | None = None) -> Iterator[str]`

The session's log, a line at a time, from the start. With `follow`, lines keep coming as they're written,
until the session is settled and its log complete (a notebook also shows its state, `progress`).

### `session.stop(checkpoint_first: bool = True) -> Session`

Stops a running session, saving a last checkpoint first unless told not to; you're charged only for the
minutes it ran.

### `session.cancel() -> Session`

Cancels a session that hasn't started; nothing is charged.

### `session.outputs`

What a completed session made: `outputs.list()` and `outputs.download(folder)`.

## `Outputs`

`session.outputs`: what a completed session made.

### `session.outputs.list() -> list[Output]`

The files, each with its size and a short-lived download link. Raises OutputsNotReady until it's complete.

### `session.outputs.download(directory: str | Path, *, on_progress: Callable[[str, int, int], None] | None = None) -> list[Path]`

Downloads every file into `directory`, keeping its path; a file already whole is left, a part-way one is
carried on. Returns where each went.

## Money

### `idleo.rupees(paise: int) -> str`

`₹1,23,456.78` for 12,345,678 paise, with Indian digit grouping; a minus sign for a negative amount.

### `idleo.paise(text: str) -> int`

Rupees a person types, to paise, exactly: `500`, `12.5` and `₹1,200.05` are 50000, 1250 and 120005.

## Exceptions

Everything the SDK raises on purpose is an `idleo.errors.IdleoError`, and each class says what the command line would exit with (`exit_code`). The API's problems have one class each, named from the problem type (`insufficient-credit` is `InsufficientCredit`); a 429 carries `retry_after`, and a 400 about invalid fields carries `fields`.

| Class | Problem type | HTTP | Exit code |
| --- | --- | --- | --- |
| `ValidationFailed` | `validation-failed` | 400 | 2 |
| `MalformedRequest` | `malformed-request` | 400 | 2 |
| `Unauthenticated` | `unauthenticated` | 401 | 5 |
| `AccountSuspended` | `account-suspended` | 403 | 1 |
| `EmailNotVerified` | `email-not-verified` | 403 | 1 |
| `InviteRequired` | `invite-required` | 403 | 5 |
| `InviteInvalid` | `invite-invalid` | 400 | 1 |
| `TermsNotAccepted` | `terms-not-accepted` | 403 | 5 |
| `NoticesChanged` | `notices-changed` | 409 | 1 |
| `AgeNotConfirmed` | `age-not-confirmed` | 422 | 1 |
| `FreshSignInRequired` | `fresh-sign-in-required` | 403 | 1 |
| `ExportInProgress` | `export-in-progress` | 409 | 1 |
| `ExportTooSoon` | `export-too-soon` | 429 | 1 |
| `DeletionBlocked` | `deletion-blocked` | 409 | 1 |
| `DeletionAlreadyRequested` | `deletion-already-requested` | 409 | 1 |
| `DeletionLinkInvalid` | `deletion-link-invalid` | 400 | 1 |
| `NoDeletionPending` | `no-deletion-pending` | 409 | 1 |
| `MessageLooksAutomated` | `message-looks-automated` | 422 | 1 |
| `TooManyAttempts` | `too-many-attempts` | 429 | 1 |
| `BrowserSignInRequired` | `browser-sign-in-required` | 403 | 5 |
| `ApiKeyReadOnly` | `api-key-read-only` | 403 | 1 |
| `AccessDenied` | `access-denied` | 403 | 1 |
| `NotFound` | `not-found` | 404 | 1 |
| `MethodNotAllowed` | `method-not-allowed` | 405 | 1 |
| `NotAcceptable` | `not-acceptable` | 406 | 1 |
| `UnsupportedMediaType` | `unsupported-media-type` | 415 | 1 |
| `NotificationLocked` | `notification-locked` | 422 | 1 |
| `TooManyApiKeys` | `too-many-api-keys` | 409 | 1 |
| `AuthorizationPending` | `authorization-pending` | 400 | 1 |
| `HostLinkDenied` | `host-link-denied` | 400 | 1 |
| `HostLinkExpired` | `host-link-expired` | 400 | 1 |
| `HostLinkApproved` | `host-link-approved` | 409 | 1 |
| `CliLoginExpired` | `cli-login-expired` | 400 | 1 |
| `RateLimited` | `rate-limited` | 429 | 6 |
| `IdempotencyKeyInvalid` | `idempotency-key-invalid` | 400 | 1 |
| `RequestInProgress` | `request-in-progress` | 409 | 1 |
| `SecretShownOnce` | `secret-shown-once` | 409 | 1 |
| `IdempotencyKeyReused` | `idempotency-key-reused` | 422 | 1 |
| `VersionRequired` | `version-required` | 428 | 1 |
| `VersionStale` | `version-stale` | 412 | 1 |
| `InsufficientCredit` | `insufficient-credit` | 402 | 3 |
| `NoGpuFree` | `no-gpu-free` | 409 | 4 |
| `SessionNotCancellable` | `session-not-cancellable` | 409 | 1 |
| `SessionNotRunning` | `session-not-running` | 409 | 1 |
| `OutputsNotReady` | `outputs-not-ready` | 409 | 1 |
| `InputNotAccepted` | `input-not-accepted` | 400 | 1 |
| `InputNotUsable` | `input-not-usable` | 422 | 1 |
| `InputRequired` | `input-required` | 400 | 1 |
| `InputTooLarge` | `input-too-large` | 400 | 1 |
| `UploadNotReady` | `upload-not-ready` | 409 | 1 |
| `UploadPartsInvalid` | `upload-parts-invalid` | 400 | 1 |
| `UploadInUse` | `upload-in-use` | 409 | 1 |
| `UploadQuotaExceeded` | `upload-quota-exceeded` | 409 | 1 |
| `TooManyNewSessions` | `too-many-new-sessions` | 429 | 6 |
| `TooManySessions` | `too-many-sessions` | 409 | 1 |
| `TooManyStreams` | `too-many-streams` | 429 | 1 |
| `PaymentsUnavailable` | `payments-unavailable` | 503 | 1 |
| `TopUpOutOfLimits` | `top-up-out-of-limits` | 422 | 1 |
| `TooManyFailedPayments` | `too-many-failed-payments` | 429 | 1 |
| `PaymentNotVerified` | `payment-not-verified` | 422 | 1 |
| `RefundAlreadyRequested` | `refund-already-requested` | 409 | 1 |
| `NothingToRefund` | `nothing-to-refund` | 422 | 1 |
| `BillingDetailsRequired` | `billing-details-required` | 409 | 1 |
| `HostBusy` | `host-busy` | 409 | 1 |
| `HostRemoved` | `host-removed` | 410 | 1 |
| `HostNoGpu` | `host-no-gpu` | 409 | 1 |
| `PauseTooLong` | `pause-too-long` | 400 | 1 |
| `NoAgentRelease` | `no-agent-release` | 404 | 1 |
| `InstallEventsFull` | `install-events-full` | 422 | 1 |
| `RequestTooLarge` | `request-too-large` | 413 | 1 |
| `SessionNotRateable` | `session-not-rateable` | 409 | 1 |
| `RatingClosed` | `rating-closed` | 409 | 1 |
| `ReportWindowClosed` | `report-window-closed` | 409 | 1 |
| `ReportAlreadyOpen` | `report-already-open` | 409 | 1 |
| `ReportAlreadyMade` | `report-already-made` | 409 | 1 |
| `AdminCodeInvalid` | `admin-code-invalid` | 400 | 1 |
| `SecondFactorExists` | `second-factor-exists` | 409 | 1 |
| `SecondFactorMissing` | `second-factor-missing` | 409 | 1 |
| `ActionRefused` | `action-refused` | 409 | 1 |
| `StorageUnavailable` | `storage-unavailable` | 503 | 1 |
| `InternalError` | `internal-error` | 500 | 1 |

And the SDK's own: `NotSignedIn` (no key found; 5), `NetworkError` (7), `UsageError` (the arguments don't fit the template, found before anything is uploaded; 2), `ServerError`, `SessionNotCompleted` (carries the session) and `WaitTimeout`.
