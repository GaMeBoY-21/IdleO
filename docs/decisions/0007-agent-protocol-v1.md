# 0007. Agent protocol v1

- **Status:** Accepted
- **Date:** 2026-10-04

Sources: docs/plan/technical-design.md lines 64, 83, 96, 102–106, 225–330, 393–401, 455, 575, 715, 761, 772; docs/prompts/phase1-step1-agent-protocol.md; ADR 0003.

## Context

The agent protocol is the one contract that can't be fixed after release. A field that the first agents in the field don't know about can never reach them.

The design doc's excerpt (technical design lines 225–330) sketched the protocol. Taken literally, it has four problems:
- It fails the buf `STANDARD` lint rules.
- It makes the agent act on its own wall clock.
- It can't tell a replaced host's late reports from the new host's.
- It has two sources of truth for host identity.

## Decision

The contract lives in `proto/gpu/agent/v1/` as a buf v2 module. It's linted with `STANDARD` plus `COMMENTS`, with no exceptions, and guarded by `FILE` breaking rules. It differs from the design doc as follows.

1. **Names follow the linter.**
   - `AgentGateway` becomes `AgentGatewayService`.
   - `rpc Connect` becomes `rpc OpenStream`. `AgentMessage` becomes `OpenStreamRequest`, and `ControlMessage` becomes `OpenStreamResponse`. The envelopes keep their contents.
   - Every enum has a `_UNSPECIFIED = 0` value and prefixed values.

   Why: buf `STANDARD`, with no exceptions. The stream is `OpenStream`, not `Connect`, so Rust's tonic generates it with default settings (see Consequences).
2. **Leases are a duration, `lease_ttl`**, on `StartSession` and `LeaseRenew`. It replaces `int64 lease_expires_at_unix_ms`. The agent's deadline is its lease clock at receipt plus `lease_ttl`.

    The lease clock keeps counting while the machine sleeps:
    - Linux: `CLOCK_BOOTTIME`. `CLOCK_MONOTONIC` stops during suspend.
    - Windows: `QueryPerformanceCounter`, which Microsoft documents as counting standby, hibernate and connected standby.
    - Never Rust's `std::time::Instant`, which doesn't specify whether suspends count.
    - On Windows hosts, the clock is read in the agent's Windows process, not inside the WSL2 VM.

    On wake, the agent checks every deadline before doing anything else. The full rules and sources are in `proto/README.md`.

   Why: agent clocks are never trusted (line 396). A clock that paused during sleep would let a laptop that slept through its lease's end wake up and keep working beside the attempt that replaced it. A clock running 3 minutes slow would keep work running 3 minutes past the lease and break fencing (line 715). A wrong or paused agent clock can't make it stop late. Transit can, by at most the transit allowance, which the control plane waits out before re-matching (ADR 0016).
3. **Every span is a `google.protobuf.Duration`.** That covers `Offer.expires_in` (was `expires_in_s`), `AgentPolicy.heartbeat_interval`, `SessionSpec.checkpoint_interval`, `SessionSpec.max_runtime` and `StopSession.grace_period`.

   Why: no message carries an absolute time for the agent to act on.
4. **`attempt` is on every session-scoped message**: `StartSession`, `LeaseRenew`, `StopSession`, `SessionStatus`, `UsageReport`, and the `RunningSession` snapshot. The control plane acks and then ignores any message whose attempt isn't the current one.

   Why: after a re-match, the old host can come back and flush its on-disk reports (line 239). Without the attempt, a minute the new host never ran could get `agent_reported` set, which breaks the two-witness rule (line 13).
5. **`StartSession.minute_index_base`.** The control plane sets it from its own clock. The agent reports `minute_index_base` plus the minutes it has run.

   Why: `minute_index` counts from the session start on the control plane's clock (lines 106, 396). A new attempt's agent counts from its own zero, so without a base, attempt 2's minute 0 would land on attempt 1's minute 0.
6. **No `host_id` in `Hello`.** The control plane derives the host from the mTLS client certificate.

   Why: the certificate fingerprint already ties the agent to its host (lines 96 and 98, the `hosts` and `host_certificates` rows). Two sources of truth for identity means one of them can lie.
7. **`Ack.acked_message_id`** (was `message_id`). An ack means "received and durably recorded", not "accepted".
   - Acked: `OfferReply`, `SessionStatus`, `UsageReport`, `StartSession`, `StopSession`, `UpdateAgent`.
   - Not acked: `Heartbeat` (the next is 5 seconds away), `LeaseRenew` (the lease survives three missed renewals), `Hello` (answered by `Welcome`), `Offer` (answered by `OfferReply`).

   Why: the old name could be confused with the envelope's own `message_id`.
8. **`message_id` and `command_id` are separate jobs.**
   - `message_id`, on every envelope, is one delivery, a UUIDv7; a retry reuses it.
   - `command_id`, on `StartSession` and `StopSession`, is business idempotency that survives reconnects and outbox re-sends.

   Why: the design doc used both without saying which does what (line 246).
9. **`Welcome` drops `lease_seconds` and `telemetry_interval`.** `AgentPolicy` carries `heartbeat_interval`, `gpu_temp_cap_c` and `max_concurrent_sessions`.

   Why: the lease travels with each session (item 2), and the heartbeat is the telemetry, so there's one implementation.
10. **Enrolment RPCs are added.**
    - `EnrollmentService.Enroll(EnrollRequest)` runs on a server-TLS-only endpoint. It takes a single-use, short-lived code and a PKCS#10 CSR, and returns the chain and `not_after`.
    - `AgentGatewayService.RenewCertificate` runs over mTLS, after 20 of 30 days.
    - Both return the shared `IssuedCertificate`.
    - The private key never leaves the host. An expired certificate can't renew, so the host re-enrols.

    Why: lines 455, 575 and 761 need these calls, and the excerpt didn't define them.
11. **Telemetry is per GPU.**
    - `Hello.gpus` lists `GpuInfo`: uuid, name, `vram_mb`, driver version, CUDA driver version, compute capability.
    - `Heartbeat.gpus` is a repeated `GpuTelemetry` keyed by uuid, replacing the single `gpu_temp_c`, `gpu_util_pct`, `vram_used_mb` and `power_w` scalars.
    - `StartSession.gpu_uuids` names the GPUs the attempt may use.

    Why: desktop hosts can have more than one GPU. Changing scalars to lists later would be a breaking change.
12. **`UpdateAgent` carries `sha256` and `cosign_signature`** (was `signature`), and `Welcome` keeps `minimum_agent_version`.

    Why: both must ship in v1 even though the updater comes later (lines 232, 242, 772). Without them, the first agents in the field could never be moved forward.
13. **All secrets live in one `Credentials` message**, commented `secret: never log`. It holds the registry token and two `StorageGrant`s: short-lived S3-compatible credentials (R2 temporary access credentials today) scoped to prefixes, with `expires_in`.
    - `attempt_grant` is read-write on `sessions/{session_id}/attempt-{n}/` only.
    - `read_grant` is read-only on the session's input and on the checkpoint named in `StartSession.resume_from`.

    `SessionSpec.env` is for non-secret values only.

    Why: Step 1 used one pre-signed URL per checkpoint and per output, and that breaks three ways:
    - A single upload caps at about 5 GB, and a 7B fine-tune checkpoint with optimizer state is bigger.
    - Every checkpoint overwrote the same object, so a bad but complete checkpoint destroyed the last good one.
    - Pre-signed URLs die after 7 days on R2.

    With grants, storage enforces fencing: an attempt can never write its input or another attempt's folder (line 715). The input moved onto `read_grant` too, because the read grant is needed for resume anyway, so each job uses one mechanism, not two. The registry token stays a plain token, because it's used once, to pull the image.
14. **Checkpoints are numbered folders with a completion marker, and `SessionStatus` is reworked.**
    - Each checkpoint goes in its own folder, `attempt-{n}/checkpoints/{seq}/`, with `_COMPLETE` written last. A folder without the marker doesn't exist for resume. The layout is in `proto/README.md`.
    - `StartSession.resume_from` (a `CheckpointRef`: prefix and seq) names the checkpoint to resume from. The control plane chooses it after checking the marker exists. It replaces `resume_checkpoint_url`.
    - `SessionStatus.latest_checkpoint_seq` replaces `checkpoints_uploaded`. The control plane checks the marker before trusting it.
    - `error` is a `SessionError` with a short code and a one-line message, never container logs, which hold renter data.
    - `progress` is dropped, because a batch container has no defined progress.
15. **Enums replace free-text fields:**
    - `OfferReply.decline_reason` is `OfferDeclineReason`.
    - `StopSession.reason` is `StopReason`.
    - `Hello.os` is `OperatingSystem`, plus an `os_version` string.

    `StopSession` also gains `attempt`, `command_id` and `grace_period`.

    Why: the control plane branches on these values, and enums are checked where free text isn't.
16. **One snapshot type.** `RunningSession` (session_id, attempt, state) is shared by `Hello.running_sessions` and `Heartbeat.sessions`.
17. **The image is pinned by digest.** `SessionSpec.image` must include `@sha256:` (line 83).
18. **No language options in the `.proto` files.** There's no `java_package` or `go_package`; buf managed mode will set them once the product name is decided.
19. **`RefreshCredentials`** (control plane → agent: `session_id`, `attempt`, `credentials`) is acked. The control plane sends it once a grant is past half its lifetime, and straight after a reconnect if a grant is close to expiring.

    Why: grants are short-lived, and long jobs outlive them. This closes Step 1's open question about pre-signed URLs expiring after 7 days.

20. **`optional` where zero means two things.**
    - `exit_code`: 0 is success.
    - `minute_index_base` and `minute_index`: 0 is the first minute.
    - `power_w`: 0 W versus "not reported".

    All four are `optional`. Every field where zero, empty or absent is never valid says so in its comment: "the receiver rejects the message".

    Why: in proto3 a plain scalar can't tell "not set" from zero.

**Deliberate omissions.** Phase 1 runs batch jobs only, and proto changes are additive, so later phases add their fields:
- `installed_games`, and every game field
- the overlay access grant
- endpoint and notebook fields; `SessionType` has only `UNSPECIFIED` and `BATCH`
- `cached_images`, which waits for the Phase 2 image and model cache

**Freeze rule.** v1 stays open to breaking changes until the first agent build is released. From that release on, changes are additive only, and the pull-request breaking check is the guard. Step 2's breaking changes were made under this rule, before any release:
- `rpc Connect` became `rpc OpenStream`; `ConnectRequest` and `ConnectResponse` became `OpenStreamRequest` and `OpenStreamResponse`.
- `Credentials` lost `input_download_url`, `checkpoint_upload_url`, `resume_checkpoint_url` and `output_upload_url` (numbers 2–5 reserved), and gained `attempt_grant` and `read_grant`.
- `SessionStatus.checkpoints_uploaded` was removed (number 4 reserved), replaced by `latest_checkpoint_seq`.
- `StartSession.minute_index_base`, `SessionStatus.exit_code`, `UsageReport.minute_index` and `GpuTelemetry.power_w` became `optional`.
- New `StorageGrant`, `StoragePermission`, `CheckpointRef`, `RefreshCredentials` and `StartSession.resume_from`. These are additive.

## Alternatives considered

- **Keep the design doc's names and add lint exceptions:** rejected. `buf.yaml` has no `except` and no `ignore`.
- **Absolute lease expiry in control-plane time, with the agent correcting for clock skew:** rejected. Skew estimates fail exactly when owners change clocks, and a duration can only err on the safe side.
- **protovalidate annotations:** not now. Validation lives in each side's code, with tests.

## Consequences

- **Both languages generate code with default settings.** In Step 1 the stream was named `Connect`, which collided with the `connect()` constructor that tonic generates on every Rust client (error E0592). That forced `.build_transport(false)` into every Rust build. Renaming the stream to `OpenStream` removed the special case.
- **The one absolute time left is `IssuedCertificate.not_after`** (a `Timestamp`). It mirrors the certificate's own expiry and is for display only. No `int64 ..._unix_ms` fields remain.
- **CI checks the contract.** `buf lint`, `buf format` and `buf breaking` (`FILE`) run on every pull request that touches `proto/`.

- **Lease deadlines survive a process restart, never a reboot.** The agent persists each deadline as the boot id plus the lease-clock value.
  - Same boot id after a restart: it restores them.
  - Different or unreadable boot id: nothing it started can still be running, since the WSL2 VM restarted. So it restores nothing, stops any container it finds, and reports those attempts as lease expired.

  This closes Step 1's open question about agent restarts.
