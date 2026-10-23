# API problem types

<!-- Generated from control-plane/src/main/java/com/idleo/controlplane/Problem.java by ProblemCatalogTests. Don't edit by hand: ./mvnw test -Dtest=ProblemCatalogTests -Dproblems.write=true -->

Every error from the API is an RFC 9457 problem detail (`application/problem+json`). Its `type` is one of the relative URIs below, `/problems/<slug>`, and never changes once published: clients branch on it, never on `title` or `detail`. The status always matches the table. `detail` says what happened this time, in words for a person. A `validation-failed` problem also has `errors`, each with a `field` (such as `params.epochs`) and a `detail`. See ADR 0019.

| Type | Status | Title | When it happens |
| --- | --- | --- | --- |
| `/problems/validation-failed` | 400 | The request has invalid fields | A field is missing, has the wrong type or is out of range. `errors` names each field, for example `params.epochs`, and what's wrong with it. |
| `/problems/malformed-request` | 400 | The request can't be read | The body isn't valid JSON, a required header is missing, or a path or query value has the wrong type. |
| `/problems/unauthenticated` | 401 | Sign-in required | No bearer token was sent, or it's invalid or has expired. Sign in again and retry. |
| `/problems/account-suspended` | 403 | Account suspended | The account is suspended, so nothing can be done with it until it's reinstated. |
| `/problems/email-not-verified` | 403 | Email not verified | The sign-in's email address isn't verified yet. |
| `/problems/invite-required` | 403 | Invite code required | Access is by invitation. Until the account is activated with an invite code, only reading and changing the account itself and activating it are allowed. |
| `/problems/invite-invalid` | 400 | Invite code not accepted | The code isn't one that can be used: it's wrong, already used or expired. The response never says which. |
| `/problems/terms-not-accepted` | 403 | Accept the Terms first | Booking, uploading and adding or pricing a machine need the current Terms and privacy notice accepted, which only a person signed in in a browser can do. The detail says where. Reading, stopping, pausing, removing and downloading are never refused for it. |
| `/problems/notices-changed` | 409 | The Terms or privacy notice changed | The versions accepted aren't the ones in force any more. Read GET /v1/me's `notices` and accept those. |
| `/problems/age-not-confirmed` | 422 | Age not confirmed | An account is only for people of the minimum age in `notices.minimumAge`; confirm it with the first acceptance. |
| `/problems/fresh-sign-in-required` | 403 | Sign in again first | This needs a sign-in in the last 10 minutes, in a browser. Sign out, sign in again, and retry. |
| `/problems/export-in-progress` | 409 | An export is being made | Your last export isn't ready yet. One is made at a time; it's emailed when it is. |
| `/problems/export-too-soon` | 429 | One export a day | An export can be asked for once a day. `Retry-After` says when the next one can be. |
| `/problems/deletion-blocked` | 409 | The account can't be deleted yet | Something has to end first. `blockers` lists each, with what to do. |
| `/problems/deletion-already-requested` | 409 | Deletion already asked for | This account's deletion is already waiting for its email confirmation, or scheduled. |
| `/problems/deletion-link-invalid` | 400 | That link doesn't work | The link isn't one that can be used: it's wrong, used already or expired. The response never says which. |
| `/problems/no-deletion-pending` | 409 | No deletion to cancel | The account isn't waiting to be deleted, or its erasure has already begun. |
| `/problems/message-looks-automated` | 422 | That looked automated | The message was sent faster than a person writes, or a field people don't see was filled. Wait a moment, then send it again. |
| `/problems/too-many-attempts` | 429 | Too many attempts | Too many invite codes were tried in the last hour. Wait, then try again. |
| `/problems/browser-sign-in-required` | 403 | Sign in with a browser for this | API keys can't manage keys, approve a CLI sign-in, add, remove or price a machine, issue its enrolment code or change notification choices. Do it in the web app, signed in. |
| `/problems/api-key-read-only` | 403 | This key can only read | A read key can only make GET requests (and revoke itself). Use a full key to change anything. |
| `/problems/access-denied` | 403 | Access denied | The caller may not do this. |
| `/problems/not-found` | 404 | Not found | There's nothing at this address, or it belongs to someone else. |
| `/problems/method-not-allowed` | 405 | Method not allowed | This address doesn't take that HTTP method. |
| `/problems/not-acceptable` | 406 | Not acceptable | The response can't be given in any media type the request's Accept header allows. |
| `/problems/unsupported-media-type` | 415 | Unsupported media type | The body's Content-Type isn't one this address takes; send application/json. |
| `/problems/notification-locked` | 422 | That notification can't be turned off | Messages about money and safety are always on, by email and in the app: stopped for credit or the spending cap, a host lost, a machine offline, payouts and top-ups, and a new device signing in. |
| `/problems/too-many-api-keys` | 409 | Too many API keys | A person can have at most 20 keys that aren't revoked. Revoke one you no longer use first. |
| `/problems/authorization-pending` | 400 | Waiting for approval | The CLI sign-in or host link hasn't been approved in the web app yet. Keep polling at the interval it gave (RFC 8628's authorization_pending). |
| `/problems/host-link-denied` | 400 | Link denied | The person who opened the link said it isn't their laptop, so it was denied. The agent should stop polling; link again from the app on the laptop if it was a mistake. |
| `/problems/host-link-expired` | 400 | Link expired or used | The link has expired (links last 10 minutes), or its enrolment code was already handed out. Link again from the app on the laptop. |
| `/problems/host-link-approved` | 409 | Link already approved | This laptop was already linked, so the link can't be denied. To take it back, remove it from Hosting. |
| `/problems/cli-login-expired` | 400 | CLI sign-in expired or used | The CLI sign-in has expired, or its key was already handed out. Start a new one with idleo login (RFC 8628's expired_token). |
| `/problems/rate-limited` | 429 | Too many requests | More requests than the limit allows: 60 a minute for each person, counted once for a session's event stream and its logs. Retry-After says how many seconds to wait. |
| `/problems/idempotency-key-invalid` | 400 | Idempotency-Key missing or invalid | A create request needs an Idempotency-Key header: 1 to 255 visible ASCII characters, without spaces. |
| `/problems/request-in-progress` | 409 | Request still in progress | A request with this Idempotency-Key is still being processed. Retry shortly with the same key. |
| `/problems/secret-shown-once` | 409 | Already created; its secret was shown once | A request with this Idempotency-Key already created it, and its secret was shown only in that first answer. Revoke it (the detail names it) and create another. |
| `/problems/idempotency-key-reused` | 422 | Idempotency-Key used for another request | This Idempotency-Key was already used with a different request body. Use a new key for a new request. |
| `/problems/version-required` | 428 | If-Match required | A change needs If-Match with the ETag from the last read of the resource. |
| `/problems/version-stale` | 412 | Changed since it was read | The resource changed since the ETag in If-Match was read. Read it again and retry. |
| `/problems/insufficient-credit` | 402 | Not enough credit | The booking's hold (30 minutes at the maximum price, up to the maximum spend) is more than the available credit. |
| `/problems/no-gpu-free` | 409 | No GPU is free | The booking left out its price ceiling, so the server uses the highest price among the GPUs that fit right now, and none fits right now. Nothing was created; try again shortly, or set a ceiling. |
| `/problems/session-not-cancellable` | 409 | Session can't be cancelled | Only a session that hasn't run (REQUESTED, MATCHED or PROVISIONING) can be cancelled; stop a running one. |
| `/problems/session-not-running` | 409 | Session isn't running | Only a RUNNING session can be stopped: cancel one that hasn't started; one that has ended can't be stopped. |
| `/problems/outputs-not-ready` | 409 | Outputs not ready | Outputs can be downloaded once the session is COMPLETED. |
| `/problems/input-not-accepted` | 400 | Template takes no such input | The input doesn't take this kind: an upload where it takes only a link, or a link where it takes only an upload. |
| `/problems/input-not-usable` | 422 | That isn't what this input needs | The upload is ready, but its files aren't what the template needs for this input (a folder without config.json for a model, a file of the wrong kind), or a zip that can't be read. The detail says what to upload instead. |
| `/problems/input-required` | 400 | Input required | An input the template needs is missing: give it an upload or a link. |
| `/problems/input-too-large` | 400 | Input too large | The upload is larger than the input takes. |
| `/problems/upload-not-ready` | 409 | Upload not ready | The upload can't be used as an input until it's ready, and can't be completed or get fresh part links once it's no longer pending: it may have failed, expired or been deleted. |
| `/problems/upload-parts-invalid` | 400 | Upload parts don't make the file | A part is missing or its ETag doesn't match what storage holds, or the finished file isn't the size the upload declared; a wrong size fails the upload. |
| `/problems/upload-in-use` | 409 | Upload in use | A session that hasn't ended uses this upload as an input; delete it once the session ends. |
| `/problems/upload-quota-exceeded` | 409 | Upload quota reached | Pending and ready uploads together would be more than the per-renter quota; delete some first. |
| `/problems/too-many-new-sessions` | 429 | Too many new sessions | More sessions booked in the last hour than the limit: 10, or 2 for an account less than a day old. Retry-After says when the next one may be booked. |
| `/problems/too-many-sessions` | 409 | Too many sessions at once | A person may have at most 3 sessions that haven't ended. Wait for one to finish, or stop one. |
| `/problems/too-many-streams` | 429 | Too many open streams | This renter already has the most event streams open at once; close one first. |
| `/problems/payments-unavailable` | 503 | Payments unavailable | Top-ups can't be taken just now: payments aren't set up here, or Razorpay can't be reached. No money moved. Try again later. |
| `/problems/top-up-out-of-limits` | 422 | Top-up outside the limits | The amount is below the smallest top-up or above the largest, or it would take today's top-ups or the bought credit you hold past their limits. The detail says which, and by how much. |
| `/problems/too-many-failed-payments` | 429 | Too many failed payments | More than 5 payments failed in the last hour, so top-ups are paused for an hour. Retry-After says when the next one may be tried. |
| `/problems/payment-not-verified` | 422 | Payment not verified | The payment's signature doesn't check out, or Razorpay says the payment isn't a captured one for this top-up's order, amount and currency. No credit was added for it. |
| `/problems/host-busy` | 409 | The host is running a session | A host can't be removed while it has a session that hasn't ended. Pause it so it takes no new work, and remove it when the session is over. |
| `/problems/host-removed` | 410 | The host was removed | This host was removed and never comes back; add the machine again to rent it out. |
| `/problems/host-no-gpu` | 409 | The host hasn't reported a GPU | A price can only be suggested once the host's agent has connected and reported its GPU. |
| `/problems/pause-too-long` | 400 | The pause is too long | A pause with an end must end within 7 days from now. Pause until you resume it for longer. |
| `/problems/no-agent-release` | 404 | No release yet | The Windows app hasn't been released yet, so there's nothing to download. Try again later. |
| `/problems/install-events-full` | 422 | Too many events for this install | One install can send at most 200 events in all. Nothing from this request was kept. |
| `/problems/request-too-large` | 413 | The request is too large | The body is bigger than this call accepts. Send less at once. |
| `/problems/session-not-rateable` | 409 | This session can't be rated | Only a session that ran for at least a minute can be rated. |
| `/problems/rating-closed` | 409 | Too late to rate this session | A session can be rated within 7 days of ending, and its rating changed within 7 days of being given. |
| `/problems/report-window-closed` | 409 | Too late to report this session | A problem with a session can be reported within 7 days of it ending. |
| `/problems/report-already-open` | 409 | This session already has an open report | A session has one report, and this one is open: we'll reply to it. |
| `/problems/report-already-made` | 409 | This session has already been reported | A session can be reported once. Its report was resolved, and what we decided is in Account, Reports. |
| `/problems/admin-code-invalid` | 400 | Code not accepted | The authenticator code or recovery code is wrong, or was already used. The fifth wrong code in 15 minutes locks admin access until an operator grants it again. |
| `/problems/second-factor-exists` | 409 | Second factor already set up | The admin's authenticator is already set up, and its secret is never shown again. Sign in with a code from it, or a recovery code. |
| `/problems/second-factor-missing` | 409 | Second factor not set up | Set up the authenticator first: ask for a secret, then confirm it with a code from the app. |
| `/problems/action-refused` | 409 | Action refused | An admin action was refused and nothing changed. The detail says why: a host without a valid certificate, say, or a reversal the wallet's available credit can't cover. |
| `/problems/storage-unavailable` | 503 | Storage unavailable | Our file storage can't be reached just now, so uploads, outputs and logs can't be read or written. Sessions keep running. Retry-After says when to try again. |
| `/problems/internal-error` | 500 | Internal error | Something failed on our side. Nothing about it is in the response; it's logged for us. |
