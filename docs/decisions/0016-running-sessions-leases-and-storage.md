# 0016. Running sessions: storage grants, leases on both sides, and losing a host

- **Status:** Accepted
- **Date:** 2026-10-06

Sources:
- docs/plan/technical-design.md lines 5–30, 69, 82, 103–120, 213–241, 242–346, 577–598, 670–679, 710–740, 779–789
- proto/gpu/agent/v1/session.proto; proto/README.md ("Storage layout", "Delivery and acks", "The lease clock")
- ADRs 0007, 0011, 0012, 0015
- docs/prompts/running-sessions.md

## Context

Booking ends with the host's agent accepting an offer and the session in PROVISIONING (ADR 0015). This makes the session run, end and recover:
- the agent gets the attempt with short-lived storage credentials and a lease;
- it runs the job, checkpointing to object storage;
- the control plane follows it to COMPLETED or FAILED;
- when a host is lost, the session resumes elsewhere without two attempts ever running at once.

Metering, settlement and charges come next. Until then an ended session releases its hold and charges nothing.

## Decision

### Storage grants, minted at send time

1. **`StorageGrants` in `sessions`** (it owns per-session credentials, line 570) mints the two grants of ADR 0007 for an attempt:
   - `attempt_grant`: read-write on `sessions/{id}/attempt-{n}/` only;
   - `read_grant`: read-only on `sessions/{id}/input/` and the resume checkpoint's folder, omitted when there's neither (input is detected by listing it).

   Grants last 1 hour (`sessions.storage.grant-ttl`). The bucket layout is exactly proto/README.md's, one bucket per environment.
2. **No secrets at rest.** A `StartSession` is queued in `agentgateway.agent_commands` without `credentials`.
   - At each delivery, the dispatcher asks the `SessionCredentials` port for fresh grants. The agent gateway declares the port and `sessions` implements it, so the dependency still points one way.
   - The port returns nothing if the attempt is no longer current, and the command is withdrawn instead of sent. A storage error leaves it queued for the next sweep.
   - Every delivery, including a resend, mints new keys. Keys exist only in the message on the wire.
   - `SessionStartTests.noStorageCredentialIsEverWrittenToATableOrALog` scans every table (row text, and hex for `bytea` payloads) and the captured log for every key, secret and token minted in a run. `LoggingTests` checks the startup log for the storage root password. On the agent, `secrets::no_storage_credential_appears_in_the_logs` checks trace-level logs while storage fails.
3. **Two adapters, chosen by `sessions.storage.provider`:**
   - **`minio`, for local runs, tests and the end-to-end test.** STS `AssumeRole` with an inline session policy naming exactly the grant's prefixes, so MinIO itself refuses anything else. `StorageGrantScopeTests` proves it with real S3 calls:
     - the attempt grant can't write the input or another attempt's folder;
     - the read grant reads only its prefixes and can't write at all.
   - **Which image:** MinIO stopped publishing images and archived its repository in April 2026. We use the maintained community fork's image, `pgsty/minio:RELEASE.2026-08-04T00-00-00Z`, pinned by digest, identical in compose.yaml and Testcontainers (`ComposeImageTests`). It's AGPL-3.0, but it's a development container we run unmodified, never shipped or linked.
   - **`r2`, for production.** Cloudflare's temporary access credentials API: `POST /accounts/{account_id}/r2/temp-access-credentials`, with the bucket, parent access key, permission (`object-read-write` or `object-read-only`), prefixes and TTL. The response carries `accessKeyId`, `secretAccessKey` and `sessionToken`. Documented at https://developers.cloudflare.com/api/resources/r2/subresources/temporary_credentials/methods/create/.
     - `R2GrantIssuerTests` checks the request and Bearer header against a recorded fixture in WireMock, and that a refusal's error carries no secret. It's never run against a live account.
     - The account, API token and parent key come from the environment (docs/runbooks/object-storage.md).
   - **The production guard refuses `minio`.**
4. **Storage calls fail fast.** They have a 5 s connect and 10 s read timeout, because minting happens on the delivery path.

### Starting an attempt

5. **When a host accepts, one transaction moves the session to PROVISIONING, records the attempt and queues its `StartSession`** (requires an ack, no expiry). The attempt goes in the new `sessions.attempts`: host, GPUs, `command_id`, minute index base, resume checkpoint, first delivery time and first RUNNING.
   - The spec is built in one place, `SessionSpecs`, for both the Offer and the StartSession.
   - The scheduler now chooses the GPU when it makes the offer: the host's largest reported GPU that isn't gone. `scheduling.offers` records it.
   - `minute_index_base` is 0 until any attempt has run. After that it's one more than the whole minutes since minute 0, from the control plane's clock, so it never lands on a minute an earlier attempt may have reported.
6. **Dedupe on both sides.** The control plane never queues a second StartSession for an attempt: a repeated accept finds the offer answered. The agent acks a repeated `command_id` and never runs it twice.
7. **The provisioning timeout (3 minutes to RUNNING) still applies,** and now catches agents that ack and never start.
8. **Two command-queue fixes the real flow exposed:**
   - **An ack can overtake the commit that marks its command sent.** The delivery transaction is still open when the agent's ack arrives, and `status = 'sent'` matched nothing, so the ack was lost. An ack now also matches `pending`: the update then waits for that commit and checks the row again. It must also come from the command's own host.
   - **Welcome is always first.** A stream is registered just before its Welcome is sent, so a command hinted in that gap could go out ahead of it. Delivery now waits until Welcome has been sent.

### Leases on both sides

9. **Control plane: `LeaseRenewals`.**
   - Every 30 s (`sessions.leases.renew-every`) it renews every attempt that is PROVISIONING or RUNNING, has had its StartSession delivered, and whose host's stream is live on this instance (heard from within the presence TTL).
   - It sends `LeaseRenew` with `lease_ttl` 2 minutes straight down the stream. It isn't queued, since the next one is 30 s away.
   - Only the instance holding a stream renews through it (`AgentStreams`, a new agent gateway API), so instances never send twice.
   - `sessions.lease_expires_at` is the send time plus `lease_ttl`, set at each StartSession delivery and renewal, and never moved earlier.
10. **The transit allowance.** The agent's deadline is its lease clock at *receipt* plus `lease_ttl`. Receipt is after the send, so the agent can stop *later* than the control plane's record, by the message's time in transit. The proto comment's "the agent can only stop early, never late" holds against wall-clock expiry, not against our record.
    - So the control plane counts a host as lost only `sessions.leases.transit-allowance` after `lease_expires_at`: 50 s, the most a message can be in flight to an agent.
    - Why 50 s: the agent pings every 30 s and drops a connection whose ping isn't answered within 20 s, discarding anything still in flight on it.
    - The control plane's view therefore never expires before the agent's, and two attempts never run at once.
    - The cost: in production a lost host is noticed about 50 s later. The end-to-end test uses 2 s.
11. **Agent: the lease clock.** It's a clock that counts while the machine sleeps, behind a `LeaseClock` trait with a fake for tests. It's never `std::time::Instant`.
    - **Linux:** `CLOCK_BOOTTIME`, through `rustix`.
    - **Windows:** `QueryPerformanceCounter`, in `agent-windows`. Microsoft documents that it includes standby, hibernate and connected standby.
    - **macOS (development only):** `CLOCK_MONOTONIC`. Apple's `clock_gettime(3)` says `CLOCK_MONOTONIC` "will continue to increment while the system is asleep", while `CLOCK_UPTIME_RAW` doesn't (Apple Libc, `gen/clock_gettime.3`: https://opensource.apple.com/source/Libc/Libc-1244.1.7/gen/clock_gettime.3.auto.html). **So on macOS `CLOCK_MONOTONIC` qualifies**, unlike Linux's, which stops during suspend.
12. **Agent: deadlines.**
    - Each attempt's deadline is the lease clock at receipt plus `lease_ttl`. A `LeaseRenew` for the current attempt extends it, never shortens it. One for any other attempt, or after the deadline, is ignored.
    - When the deadline passes, the attempt's fence goes down and its tasks are aborted. It's reported `LEASE_EXPIRED` and never resumes.
    - Deadlines are checked every 250 ms whether a stream is open or not, and first in every entry point (a message, a runner report, a status send). So after waking from sleep, the first thing that happens is the stop.
    - `max_runtime` is a hard cap the same way.

### Reconciliation on reconnect

13. **The agent gateway declares `SessionReconciliation`; `sessions` implements it.** It's called with the `Hello`'s `running_sessions` before the new stream carries anything.
    - **A session the control plane thinks is running there but the agent doesn't list is lost at once.** That means PROVISIONING or RUNNING, with its StartSession delivered before this stream connected.
    - **A session the agent lists that the control plane has moved on from gets a `StopSession` with reason `SUPERSEDED`.** That covers another attempt, another host, a final state, or an unknown session.
    - StopSessions go through the command queue, acked, and expire when the lease would have stopped the work anyway.
    - Cancelling a session in PROVISIONING whose StartSession was delivered also sends a StopSession (`RENTER_REQUESTED`).

### The agent runs the job

14. **`SessionRunner` trait. The only runner is `dev`**, chosen with `--dev-runner`, which the agent refuses without `--fake-gpu` (clap, and again in code).
    - It goes PREPARING, then RUNNING, for the `--duration` in the spec's arguments, writing a checkpoint every `checkpoint_interval`. Each checkpoint is a numbered folder (8 digits) with `state.json`, then a `_COMPLETE` manifest of each file and its size.
    - It then writes `outputs/result.json`, naming the attempt and the checkpoint it resumed from, then `outputs/_COMPLETE`, and reports SUCCEEDED with exit code 0.
    - On resume it reads the `resume_from` checkpoint through the read grant, only if its manifest matches, and carries on from the time recorded there.
    - An agent without a runner declines every offer (`INSUFFICIENT_RESOURCES`) and fails any StartSession with `no_runner`. It never falls back silently.
    - **The Docker runner, with the sandbox flags of technical design "Sandbox configuration", is not built yet.** It's the next agent work, on the Windows host.
15. **The S3 client: `rusty-s3` 0.10 (BSD-2-Clause, added to `deny.toml`), with `reqwest` 0.13 over our own rustls config.**
    - rusty-s3 only signs SigV4 requests, with the grant's session token. It does no I/O and never looks for credentials of its own, so an agent can't pick up ambient cloud keys.
    - reqwest uses the ring provider the agent already has, and the operating system's trust store (`rustls-platform-verifier`), since storage presents a public certificate. The control plane connection still trusts only our root.
    - Rejected: `rust-s3` (pulls in `sysinfo`, its own credential loader and native TLS by default); `aws-sdk-s3` (heavy, and a second crypto stack); `object_store` (much more than we need).
    - The agent checks every key against the grant's prefixes, and refuses writes through a read-only grant, before any request. Storage enforces the same. Grants print without their secrets, and transport errors are reported without their signed URLs.
16. **`SessionStatus`** goes out on every state change and completed checkpoint, with `latest_checkpoint_seq`, `exit_code` and `error`. Statuses are kept until acked, resent every 5 s, and all resent on a new stream. They're in memory for now; the persistent store comes with the Docker runner. An agent restart loses its attempts, and the control plane's lease handles them.

### The control plane follows the job

17. **The agent gateway declares `SessionStatuses`; `sessions` implements it.** The gateway acks only after it returns, and drops a malformed status without an ack.
    - Only the current attempt on its current host counts. Anything else is acked and ignored, so a host that comes back after a re-match changes nothing.
    - **The first RUNNING:** PROVISIONING to RUNNING, and `sessions.started_at`, minute 0, is set on the control plane's clock.
    - **A checkpoint:** recorded in `sessions.checkpoints` only once its `_COMPLETE` marker exists in storage.
    - **SUCCEEDED:** RUNNING to FINISHING. Then COMPLETED once `attempt-{n}/outputs/_COMPLETE` exists, or FAILED with `outputs_missing` after 2 minutes (checked by a sweep).
    - **FAILED or a non-zero exit:** FAILED, with the agent's error code if it's well formed (`[a-z0-9_]{1,64}`), else `exit_code_N`.
    - **LEASE_EXPIRED or STOPPED** for the current attempt means the host is lost at once: the agent has stopped.
    - Every end state releases the hold.
18. **State machine additions** (the design's table is updated to match):
    - PROVISIONING to HOST_LOST: the agent holds a lease from delivery, so a host can be lost before RUNNING;
    - RUNNING to FAILED when the job fails;
    - FINISHING to FAILED when outputs never appear;
    - **HOST_LOST to REQUESTED** (the design had MATCHED): the session goes back through the scheduler as booking;
    - HOST_LOST to FAILED after too many lost attempts.

### Losing a host

19. **A host is lost when one of these happens:**
    - its lease plus the transit allowance has passed (a `SKIP LOCKED` sweep);
    - its agent reports LEASE_EXPIRED or STOPPED;
    - reconciliation finds the attempt missing.
20. **What happens to the session:**
    - It moves to HOST_LOST, then REQUESTED as attempt plus one, and publishes `SessionRequested` (durable). The scheduler books it as before, possibly back onto the same host once it's online again.
    - **The one hold is kept** across the re-match and its offers. Only a first attempt's hold is released on a declined offer.
    - **The resume checkpoint is the newest earlier checkpoint whose `_COMPLETE` is in storage, found by listing storage,** so one finished just before the host died still counts. A higher attempt's checkpoint is newer, since attempts never overlap. It's recorded in `sessions.checkpoints` if it wasn't already.
21. **The attempt cap.** After 3 lost attempts (`sessions.max-lost-attempts`), the session is FAILED with `host_lost_repeatedly`, and the hold is released.
22. **Waiting for a host.** A first attempt with no candidate is NO_HOST_FREE at once (ADR 0015). A re-matched session has already run, so it waits in REQUESTED instead.
    - It's retried every 5 s (`scheduling.rematch-retry-every`) for up to 10 minutes (`scheduling.rematch-wait`). After that it's NO_HOST_FREE, with the hold released.
23. **`GET /v1/sessions/{id}/outputs`**, owner only (others get 404) and only when COMPLETED (409 otherwise). It lists the successful attempt's `outputs/` files, without the marker, each with a pre-signed GET link valid for 15 minutes. These are single-object downloads for the renter, not agent uploads.

### The development catalog

24. **`templates/development.yaml`** holds the smoke test as the development runner runs it: `--duration 20s`, a checkpoint every 5 s.
    - It's selected with `SESSIONS_TEMPLATES_CATALOG` (the end-to-end test does) and isn't bundled.
    - The production guard refuses any catalog but the bundled one.

## Deliberately not built yet

- The Docker runner and its sandbox flags, image pulls, `registry_token` and cosign.
- The agent's persistent store (ADR 0007's boot-id deadlines across restarts).
- `RefreshCredentials`: grants last an hour, and test jobs are short.
- Usage reports, metering, settlement and charges.
- The renter's `/stop` for running sessions.
- Cleaning up checkpoints 7 days after a session ends.
- Web screens.

## Alternatives considered

- **Storing credentials in the queued command and redacting them later:** rejected. A secret at rest is a secret in backups, replicas and query logs. Minting at delivery costs one STS or R2 call per delivery.
- **Pre-signed URLs for agent uploads:** rejected (ADR 0007). Grants scope a whole folder, storage enforces them, and one mechanism serves input, checkpoints and outputs.
- **Recording `lease_expires_at` as exactly send time plus `lease_ttl`, and re-matching then:** it lets a delayed renewal keep the old attempt running past the re-match by its transit time. Hence the allowance.
- **The agent subtracting a margin from `lease_ttl`:** that changes the protocol's meaning for every agent release. The control plane waiting longer changes nothing on the wire.
- **Re-matching straight to MATCHED:** that skips the scheduler's candidate checks and holds. Going back to REQUESTED reuses booking unchanged.
- **Trusting `latest_checkpoint_seq` for resume:** rejected. The control plane checks markers itself, and lists storage so a checkpoint finished just before the host died isn't lost.
- **NO_HOST_FREE at once for a re-match:** rejected. A session that already ran and checkpointed would end because its host blinked. It waits, bounded.

## Consequences

- **A booked session now runs, completes and recovers from a lost host.** The end-to-end test does all of it on every change: the run completes, the renter downloads its output, and the agent is killed and the session resumes as attempt 2.
- **No storage secret is stored or logged anywhere.** Tests prove it on both sides.
- **In production, a lost host is noticed 2 minutes plus 50 seconds after its last renewal.** That's the price of never running two attempts at once.
- **An agent restart loses its running attempts and unsent statuses** until the persistent store exists. Leases and reconciliation make that safe, not free.
- **Before production storage, Nikki creates the R2 bucket, parent key and API token** (docs/runbooks/object-storage.md). Nothing is created now.
