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
| `/problems/access-denied` | 403 | Access denied | The caller may not do this. |
| `/problems/not-found` | 404 | Not found | There's nothing at this address, or it belongs to someone else. |
| `/problems/method-not-allowed` | 405 | Method not allowed | This address doesn't take that HTTP method. |
| `/problems/not-acceptable` | 406 | Not acceptable | The response can't be given in any media type the request's Accept header allows. |
| `/problems/unsupported-media-type` | 415 | Unsupported media type | The body's Content-Type isn't one this address takes; send application/json. |
| `/problems/idempotency-key-invalid` | 400 | Idempotency-Key missing or invalid | A create request needs an Idempotency-Key header: 1 to 255 visible ASCII characters, without spaces. |
| `/problems/request-in-progress` | 409 | Request still in progress | A request with this Idempotency-Key is still being processed. Retry shortly with the same key. |
| `/problems/idempotency-key-reused` | 422 | Idempotency-Key used for another request | This Idempotency-Key was already used with a different request body. Use a new key for a new request. |
| `/problems/version-required` | 428 | If-Match required | A change needs If-Match with the ETag from the last read of the resource. |
| `/problems/version-stale` | 412 | Changed since it was read | The resource changed since the ETag in If-Match was read. Read it again and retry. |
| `/problems/insufficient-credit` | 402 | Not enough credit | The booking's hold (30 minutes at the maximum price, up to the maximum spend) is more than the available credit. |
| `/problems/no-gpu-free` | 409 | No GPU is free | The booking left out its price ceiling, so the server uses the highest price among the GPUs that fit right now, and none fits right now. Nothing was created; try again shortly, or set a ceiling. |
| `/problems/session-not-cancellable` | 409 | Session can't be cancelled | Only a session that hasn't run (REQUESTED, MATCHED or PROVISIONING) can be cancelled; stop a running one. |
| `/problems/session-not-running` | 409 | Session isn't running | Only a RUNNING session can be stopped: cancel one that hasn't started; one that has ended can't be stopped. |
| `/problems/outputs-not-ready` | 409 | Outputs not ready | Outputs can be downloaded once the session is COMPLETED. |
| `/problems/input-not-accepted` | 400 | Template takes no such input | The template takes no input, or not of this kind (an upload or a URL). |
| `/problems/input-required` | 400 | Input required | The template needs an input: an upload or a URL. |
| `/problems/input-too-large` | 400 | Input too large | The upload is larger than the template's largest input. |
| `/problems/upload-not-ready` | 409 | Upload not ready | The upload can't be used as an input until it's ready, and can't be completed or get fresh part links once it's no longer pending: it may have failed, expired or been deleted. |
| `/problems/upload-parts-invalid` | 400 | Upload parts don't make the file | A part is missing or its ETag doesn't match what storage holds, or the finished file isn't the size the upload declared; a wrong size fails the upload. |
| `/problems/upload-in-use` | 409 | Upload in use | A session that hasn't ended uses this upload as its input; delete it once the session ends. |
| `/problems/upload-quota-exceeded` | 409 | Upload quota reached | Pending and ready uploads together would be more than the per-renter quota; delete some first. |
| `/problems/too-many-streams` | 429 | Too many open streams | This renter already has the most event streams open at once; close one first. |
| `/problems/internal-error` | 500 | Internal error | Something failed on our side. Nothing about it is in the response; it's logged for us. |
