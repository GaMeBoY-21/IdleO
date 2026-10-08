# 0019. The booking contract: error types, parameters and inputs, uploads, logs and one live stream

- **Status:** Accepted
- **Date:** 2026-10-08

Sources:
- docs/plan/product-surface.md sections 2, 3.2, 4.2, 10 and 11.1–11.7, 11.11 (accepted by Nikki on Oct 7, section 12)
- docs/plan/technical-design.md "REST API", "Errors", "Limits", "Data stores", "Agent protocol"
- proto/README.md "Storage layout"; ADRs 0007, 0011, 0015, 0016, 0017, 0018
- docs/prompts/booking-contract.md

## Context

The next round draws the booking and live-session screens, and the CLI and the SDK follow. Before any of them is drawn, the API they share must be able to:
- tell errors apart by machine (every problem detail was `about:blank`, and two different 409s differed only in English);
- take a template's settings and an input file (nothing could tell a fine-tune what to train on);
- take a file from a renter's laptop without the control plane carrying it;
- quote a booking before it's made, and show what's available, without naming hosts;
- show a session live, with its logs, through one stream that the web app, the CLI and the SDK all read.

Every endpoint here serves all three clients, so none is shaped for the web app alone.

## Decision

**One error catalog.**
- The enum `Problem` in the shared kernel holds every problem type: a slug, an HTTP status and a fixed title.
- A problem detail's `type` is the relative URI `/problems/<slug>`. RFC 9457 section 3.1.1 allows a relative reference, so the domain can be chosen later without breaking clients.
- Errors are raised only as `ApiProblem.of(Problem, detail)`. A test fails if a `ResponseStatusException` or a bare `ProblemDetail` is built anywhere else, and a test-wide filter fails any test that receives a problem without a catalog type, or whose status isn't its type's.
- `ProblemDetailsAdvice` types Spring's own errors too, keeping the status Spring chose: malformed JSON, missing headers and type mismatches are `malformed-request`; 404 is `not-found`; 405, 406 and 415 have their own types; field validation is `validation-failed` with `errors[]`; a 500 is `internal-error` with a generic detail.
- `docs/api/problems.md` is generated from the enum, and a test fails when the committed file differs. The OpenAPI `ProblemDetail.type` enumerates the types, so generated clients get them as a union.
- Statuses are unchanged: this added types without changing behaviour.
- Another renter's session or upload stays `not-found`, never a 403.

**Templates describe their parameters and input once.**
- `templates/catalog.yaml` gives each template a description, an optional typical runtime, `params` (name, label, help, type, required, default and limits) and `input` (none, optional or required; upload, URL or both; the largest upload).
- The control plane refuses to start on a malformed entry, an unknown field, or a default that fails its own parameter's rules.
- `GET /v1/templates` serves all of it, and every surface's form, flags and arguments are generated from it.
- One validator in the sessions module, `SessionRequests`, checks a request against its template, and booking and quoting both call it.

**Parameters reach the job only as environment variables.**
- Each is `SESSION_PARAM_<NAME>`: integers as plain decimals, numbers as `BigDecimal.toPlainString()`, booleans as `true` or `false`, strings and choices as given.
- An optional one without a default is absent, never an empty string. An unknown name is refused, never ignored.
- Strings refuse control characters other than tab and newline, and the variables together stay under 32 KiB.
- The resolved values are kept as JSON on the session and shown on it.
- Secrets never go in parameters: secret parameters, such as a Hugging Face token, are a later design through `Credentials`.

**Uploads go straight to storage.**
- `POST /v1/uploads` opens a multipart upload in storage and signs a link per part, valid for an hour; every part but the last is 64 MiB. Clients PUT the parts and complete the upload with their ETags.
- Completing checks the stored object's size against the declared size: a mismatch deletes the object and fails the upload.
- The object is `uploads/{upload_id}/{stored_name}`. The original filename is kept for display; the stored name keeps only `[A-Za-z0-9._-]`.
- Limits: 20 GiB per file, and 25 GiB of pending and ready uploads per renter. A pending upload is aborted after a day, and a ready one is deleted 30 days after it completed, never while a session that hasn't ended uses it.
- A repeated create (the same Idempotency-Key and body) returns the same upload with freshly signed links. This is why uploads keep their key on their own row, as sessions do, rather than through `IdempotentRequests`: its stored response would hold signed links.
- Storage allows CORS PUT from the web app's origins only, and exposes `ETag`.

**A session's input.**
- A session takes `params` and an `input`: exactly one of a ready upload of the renter's own, or an https URL.
- `StartSession.input` (an `InputRef`: prefix, name, size) names an uploaded input, and the attempt's read grant covers its folder, beside the resume checkpoint. `sessions/{id}/input/` is gone from the layout: uploads replace it, so there's one way, not two.
- The agent's supervisor, not a runner, stages the input into the attempt's scratch `input/` folder while PREPARING, before minute 0, and checks its size. After 3 tries the attempt fails `input_unavailable`, and nothing is billed.
- The provisioning timeout grows by the input's size at 4 MB/s, so a large dataset on home internet isn't taken for a failed start.

**The agent never fetches a URL.**
- A URL input reaches the job only as `SESSION_INPUT_URL`, and the job fetches it inside its sandbox, where private networks are blocked.
- The agent runs outside the sandbox and could reach the host's home network, so fetching a renter's URL would let a renter probe it.
- The control plane also refuses URLs that aren't https, carry user info, or name localhost, or a private, loopback, link-local or shared-address literal. It never resolves names: the sandbox is the real guard.

**Availability and quotes.**
- `GET /v1/availability` gives one row per GPU model and memory among active, listed, online hosts: how many are online, how many could take a session now, and their prices (min, median, max). It never names a host.
- `POST /v1/session-quotes` takes the booking's body, runs the same validator, and returns the hold (`CreditReservations.holdFor`, the same call booking makes), the available credit, and the hosts that fit now with their price range. It reserves nothing.
- Both live in the scheduling module and use the scheduler's own hard filters (`CandidateFinder`), fed by one hosts query (`SchedulableHosts.batchCandidates`). This is why they're there: product surface 10.3 puts availability in hosts and quotes in sessions, but neither of those may depend on scheduling (ADR 0015), and both need its filters. Copying the filters would let a quote say two hosts fit while booking finds none.

**The session list pages.** `GET /v1/sessions` takes an opaque `cursor`, a `limit` (20 by default, at most 100) and `active`, newest first by id.

**Logs.**
- The agent's log sink is independent of any runner: a runner writes bytes, and the sink uploads a chunk every 5 seconds or 64 KiB to `sessions/{id}/attempt-{n}/logs/{seq}.log` through the attempt grant. It keeps at most 10 MiB per attempt, then one line saying so.
- Failed uploads are retried in order on the next tick. A log upload never blocks, slows or fails the job, and the last chunk is flushed before the terminal status is sent, for at most 10 seconds.
- `latest_log_seq`, on `RunningSession` (in heartbeats) and on `SessionStatus`, reports the highest chunk uploaded. The control plane keeps it per attempt, only raises it, and accepts it only from the host that ran that attempt. So it never lists storage, and reads only keys it knows exist.
- `GET /v1/sessions/{id}/logs` pages chunks by attempt then seq, as UTF-8 with invalid bytes replaced. Its `complete` flag is true once the session is settled and every chunk has been returned. Only the renter reads logs.

**One live stream.**
- `GET /v1/sessions/{id}/events` is Server-Sent Events. `session` events carry the same view as `GET /v1/sessions/{id}`, on connect and whenever it changes; `logs` events carry an attempt's newest chunk. A comment every 15 seconds keeps a quiet stream open.
- It closes after the last `session` event once `settled` is true, and after 30 minutes in any case, so clients reconnect with a fresh token.
- After commit, every transition, settlement, checkpoint and log advance sends a hint on a Valkey channel. The pattern is the command queue's, now in one shared place (`ValkeyHints`): published after commit, subscribed in the background, never in readiness.
- Each open stream also re-reads every 5 seconds and sends only what differs. That covers the per-minute accrual, a lost hint, and Valkey being down.
- Clients read it with `fetch` and a bearer token; nothing depends on cookies or a token in the URL. At most 10 streams per renter.

**`settled` and `endedAt`.**
- `endedAt` is when the session reached its final state.
- `settled` is true once it has ended and holds no credit, and, if it ran, metering has closed it: its last settlement is posted and its hold released.
- This is the one definition of "nothing will change any more", and the web app watches a session until it's true.
- Each session also shows the GPU of its current or latest attempt (model and memory, never the host) and its complete checkpoints.

## Alternatives considered

- **Absolute problem type URIs** (`https://docs.example/errors/...`, as the technical design's example). Lost: the domain isn't chosen, and changing it later would break every client that compares types.
- **Proxying uploads through the control plane.** Lost: it would carry up to 20 GiB per file through the API's memory and bandwidth; pre-signed multipart links put the bytes on storage directly.
- **The agent fetching URL inputs, like uploads.** Lost: the agent is outside the sandbox and on the host's home network.
- **Keeping inputs under `sessions/{id}/input/`, copied from the upload.** Lost: two ways to the same file, and a copy of every dataset for every session.
- **Listing storage to find log chunks.** Lost: listing is slower, costs more on R2, and would trust whatever an attempt wrote; the reported seq names only what exists.
- **WebSockets for the live view.** Lost: one-way updates are what's needed, SSE works through proxies and with `fetch`, and the CLI reads it with plain HTTP.
- **A Valkey-backed count of open streams across instances.** Lost for now: it would put Valkey in the request path for opening a stream. The cap is per instance, which is one instance in Phase 1.

## Consequences

- Clients branch on `type`, never on English, and a new error needs a catalog entry, which shows in review as a diff to `docs/api/problems.md`.
- A new template parameter is one catalog edit, and every surface picks it up.
- Uploads need storage's CORS rule (docs/runbooks/object-storage.md) before the browser can upload in production.
- The agent now writes to its data folder (`work/`), and wipes each attempt's folder at the end and the whole folder at start.
- A terminal status can wait up to 10 seconds for logs to flush.
- **Open question:** the per-renter stream cap is per instance; it needs a shared count once there's more than one instance (Phase 3, multi-region).
- **Open question:** secret parameters (tokens a job needs) go through `Credentials`, designed later.
- **Open question:** old logs are kept as long as outputs; their deletion comes with output retention.
