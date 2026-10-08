# proto

The shared .proto files that define the agent protocol, compiled for both Rust and Java and checked by buf.

Starts in Phase 1 (compute core), with host agent v0 and the control plane it talks to.

## Layout

A buf v2 module. `buf.yaml` sits here, and package `gpu.agent.v1` sits in `gpu/agent/v1/`:

| File | What it holds |
| --- | --- |
| `agent_gateway.proto` | `AgentGatewayService` (`OpenStream` stream, `RenewCertificate`); the `OpenStreamRequest` and `OpenStreamResponse` envelopes; `Hello`, `Welcome`, `Ack`, `UpdateAgent` |
| `session.proto` | `SessionSpec`, `Credentials`, `StorageGrant`, `CheckpointRef`, session states; `Offer`, `OfferReply`, `StartSession`, `LeaseRenew`, `RefreshCredentials`, `StopSession`, `SessionStatus`, `UsageReport`; `RunningSession`, the one snapshot type shared by `Hello` and `Heartbeat` |
| `telemetry.proto` | `GpuInfo`, `GpuTelemetry`, `Heartbeat`, `OperatingSystem` |
| `enrollment.proto` | `EnrollmentService` (`Enroll`, server-TLS only); `IssuedCertificate`, shared with `RenewCertificate` |

The `.proto` files carry no `java_package`, `go_package` or other language options. Generation sets them later through buf managed mode, once the product name is decided.

The design decisions behind the contract, and how it differs from the design doc, are in `docs/decisions/0007-agent-protocol-v1.md`.

## Commands

Run from this folder:

```
buf lint         # STANDARD + COMMENTS, no exceptions
buf format -d    # must print nothing; buf format -w fixes it
buf build
buf breaking --against '../.git#branch=main,subdir=proto'   # FILE rules
```

CI (`.github/workflows/proto.yml`) runs:
- **On pull requests:** lint, format and the breaking check against the base branch.
- **On pushes to main:** lint and format.

It runs only when `proto/**` or the workflow itself changes.

### Code generation notes

No generated code is committed. Each side generates its own when it's built, with default settings. The stream is named `OpenStream` rather than `Connect`, so it doesn't collide with the `connect()` constructor that tonic generates on every Rust client.

## Compatibility rules

**When v1 freezes.** v1 stays open to breaking changes until the first agent build is released. From that release on, these rules apply, and the pull-request breaking check (`buf breaking`, `FILE` rules) is the guard.

- **Additive only.** Add new fields, messages, enum values and RPCs. Never change a field's type, name, number or meaning.
- **Removed fields are `reserved`**, both number and name, so they can never come back with another meaning.
- **Never renumber** fields or enum values.
- **The last three agent releases stay supported** by the control plane. `Welcome.minimum_agent_version` and `UpdateAgent` move older agents forward.
- **`buf breaking` with `FILE` rules** guards all of this in CI.

## Delivery and acks

- **`message_id`**, on every envelope, identifies one delivery, as a UUIDv7. A retry reuses it, so the receiver drops duplicates.
- **`command_id`**, on `StartSession` and `StopSession`, is business idempotency. It survives reconnects and outbox re-sends, and a repeated command is acked and never run twice.
- **`Ack.acked_message_id`** names the envelope being acked. An ack means "received and durably recorded", not "accepted". The sender keeps its retry copy until the ack arrives. Acks are never acked.

| Message | Direction | Acked? | Why | Handled by the control plane today |
| --- | --- | --- | --- | --- |
| `Hello` | agent → control plane | No | Answered by `Welcome` | Yes: validated, GPUs and versions recorded |
| `Welcome` | control plane → agent | No | Sent again on every new stream | Yes: sent after a valid `Hello` |
| `Heartbeat` | agent → control plane | No | The next one is 5 seconds away | Yes: validated, refreshes presence in Valkey |
| `Offer` | control plane → agent | No | Answered by `OfferReply` | Yes: sent by the scheduler through the command queue (ADR 0015) |
| `OfferReply` | agent → control plane | Yes | | Yes: recorded by the scheduler, then acked (ADR 0015) |
| `StartSession` | control plane → agent | Yes | | Yes: queued without credentials; fresh grants minted at each delivery (ADR 0016) |
| `LeaseRenew` | control plane → agent | No | The lease survives three missed renewals | Yes: every 30 s by the instance holding the stream (ADR 0016) |
| `SessionStatus` | agent → control plane | Yes | Kept on disk until acked (in memory until the agent's store exists) | Yes: applied by the sessions module, then acked (ADR 0016) |
| `UsageReport` | agent → control plane | Yes | Kept on disk until acked | Not yet: counted and dropped, never acked |
| `StopSession` | control plane → agent | Yes | | Yes: `SUPERSEDED` on reconnect, `RENTER_REQUESTED` on a cancel after the start (ADR 0016) |
| `UpdateAgent` | control plane → agent | Yes | | Not yet (agent releases) |
| `RefreshCredentials` | control plane → agent | Yes | | Not yet (sessions) |
| `Ack` | both | No | Acks are never acked | Yes: marks the agent's command acked |

Messages that aren't handled yet arrive with their features. Until then the control plane counts them and drops them; it never acks them, so an agent keeps its on-disk copies.

The unary calls: `EnrollmentService.Enroll` is handled (ADR 0010). `AgentGatewayService.RenewCertificate` is handled in a certificate's last 10 days (ADR 0011).

## Time and attempts

- **No message carries an absolute time for the agent to act on.**
  - Spans are `google.protobuf.Duration`.
  - The lease is `lease_ttl`: the agent's deadline is its lease clock at receipt plus `lease_ttl`. A wrong or paused agent clock can't make it stop late. Transit can, by at most the transit allowance, which the control plane waits out before re-matching (ADR 0016). The lease clock keeps counting while the machine sleeps; see below.
  - The one `Timestamp`, `IssuedCertificate.not_after`, mirrors the certificate's own expiry, for display only.
- **Every session-scoped message carries `attempt`.** The control plane acks and then ignores any message whose attempt isn't the current one.
- **`StartSession.minute_index_base`** maps this attempt's first minute onto the session-wide minute index.

## The lease clock

A laptop that sleeps must not run past its lease. Say a lid closes mid-job, the control plane re-matches the job elsewhere, and the laptop wakes 20 minutes later. A clock that paused during sleep would let the old attempt keep running beside the new one. So the agent follows these rules (agent/agent/src/lease_clock.rs and supervisor.rs, ADR 0016).

**1. Deadlines use a clock that keeps counting while the machine sleeps.**
- **Linux:** `clock_gettime(CLOCK_BOOTTIME)`. The man page says it "also includes any time that the system is suspended". `CLOCK_MONOTONIC` "does not count time that the system is suspended", so it's never used for deadlines.
- **Windows:** `QueryPerformanceCounter`. Microsoft's documentation says it returns the ticks since Windows started, "including the time when the machine was in a sleep state such as standby, hibernate, or connected standby". `QueryUnbiasedInterruptTime` excludes sleep, so it's never used.
- **Not Rust's `std::time::Instant`.**
  - It uses `CLOCK_MONOTONIC` on Linux, which stops during suspend, and `QueryPerformanceCounter` on Windows.
  - Rust's documentation says whether suspends count "varies across platforms and Rust versions".
  - So the agent calls the OS clock directly.
- **On Windows hosts, the clock is read in the agent's Windows process, never inside the WSL2 VM.** Nothing documents that the VM's clocks count the host's sleep.

- **macOS (development only):** `CLOCK_MONOTONIC`. Apple's `clock_gettime(3)` says it "will continue to increment while the system is asleep", unlike `CLOCK_UPTIME_RAW`; Linux's `CLOCK_MONOTONIC` doesn't, which is why Linux uses `CLOCK_BOOTTIME` (ADR 0016).
- **The control plane's side:** it records each lease as its send time plus `lease_ttl`. The agent counts from receipt, after the send, so the control plane counts a host as lost only a transit allowance (50 s) after its own record: the most a message can be in flight before the agent's keepalive drops the connection (ADR 0016).

**2. On wake, check every deadline first.** When the OS reports a resume, the agent checks every deadline before doing anything else. It stops each attempt whose deadline has passed.

**3. Deadlines survive a process restart, but never a reboot.** (Not built yet: the agent keeps its attempts in memory until its persistent store exists, so a restart loses them and the control plane's lease handles them, ADR 0016.)
- The agent persists each deadline as the **boot id** plus that clock's value.
  - Linux boot id: `/proc/sys/kernel/random/boot_id`.
  - Windows boot id: `Win32_OperatingSystem.LastBootUpTime`.
- **Same boot id after a process restart:** restore the deadlines and check them at once.
- **Different boot id, or one it can't read:** treat it as a reboot. Nothing the agent started can still be running, because the WSL2 VM restarted with the machine. So the agent:
  - restores nothing
  - stops and removes any session container it finds
  - reports those attempts as `SESSION_STATE_LEASE_EXPIRED`

  Any doubt counts as a reboot, because stopping is the safe direction.

Sources:
- clock_gettime(2): https://man7.org/linux/man-pages/man2/clock_gettime.2.html
- Linux boot_id: https://docs.kernel.org/admin-guide/sysctl/kernel.html
- Acquiring high-resolution time stamps: https://learn.microsoft.com/en-us/windows/win32/sysinfo/acquiring-high-resolution-time-stamps
- Interrupt time: https://learn.microsoft.com/en-us/windows/win32/sysinfo/interrupt-time
- Win32_OperatingSystem: https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-operatingsystem
- Rust's std::time::Instant: https://doc.rust-lang.org/std/time/struct.Instant.html
- Apple clock_gettime(3): https://opensource.apple.com/source/Libc/Libc-1244.1.7/gen/clock_gettime.3.auto.html

## Storage layout

Jobs move data through S3-compatible object storage (R2 today). There are no pre-signed URLs. Each attempt gets short-lived credentials (`StorageGrant`), scoped to prefixes and enforced by storage, and the control plane refreshes them with `RefreshCredentials`. One bucket holds:

```
uploads/{upload_id}/
  {name}                     a renter's upload, a session's input (ADR 0019);
                             agents can only read it, through the read grant
sessions/{session_id}/
  attempt-{n}/               one folder per attempt; only attempt n can write here
    checkpoints/
      {seq}/                 one folder per checkpoint, seq = 1, 2, 3 ...,
        ...                  written as 8 zero-padded digits (00000001)
        _COMPLETE            completion marker, written last
    outputs/
      ...
      _COMPLETE              completion marker, written last
    logs/
      {seq}.log              the job's output, one chunk each, seq = 1, 2, 3 ...,
                             written as 8 zero-padded digits (00000001.log)
```

The control plane writes `uploads/` itself, through pre-signed multipart links the renter's client PUTs to; nothing an agent holds can write there. An upload belongs to its renter, not to one session, so the same file can be several sessions' input.

### Grants

Each attempt gets exactly two grants, inside `Credentials`:

| Grant | Permission | Prefixes |
| --- | --- | --- |
| `attempt_grant` | read-write | `sessions/{session_id}/attempt-{n}/` |
| `read_grant` | read-only | `uploads/{upload_id}/`, the folder of `StartSession.input`, and the folder of `StartSession.resume_from`, if any; absent when there's neither |

An attempt can't write its input or another attempt's folder. That's fencing enforced by storage, not just by the agent behaving.

### Rules

**Completion markers**
- `_COMPLETE` is written last, after every other file in the folder has uploaded.
- It's a JSON manifest that lists each file's path, relative to the folder, and its size in bytes.
- A folder without `_COMPLETE` doesn't exist as far as resume or outputs are concerned. A crash mid-upload leaves no usable half-checkpoint.

**Checkpoints**
- Each checkpoint goes in a new folder, numbered one higher than the last.
- A completed checkpoint folder is never overwritten or deleted by the agent. A bad checkpoint can never destroy the last good one.
- Old checkpoints are cleaned up by the control plane, 7 days after the session ends.

**Resume**
- The control plane picks the checkpoint to resume from: the newest folder that has `_COMPLETE`.
- It checks the marker exists, then names the checkpoint in `StartSession.resume_from` (prefix and seq).
- `SessionStatus.latest_checkpoint_seq` reports the agent's newest completed checkpoint. The control plane checks the marker before trusting it.

**Outputs**
- A job's outputs are complete only when `outputs/_COMPLETE` exists.
- The agent reports `SESSION_STATE_SUCCEEDED` only after writing it.

**Inputs** (ADR 0019)
- `StartSession.input` names an uploaded input: its folder, its file name and its size. The agent downloads it into the attempt's scratch folder, `input/{name}`, while PREPARING, before minute 0, checks its size, and tries 3 times before failing the attempt with `input_unavailable`. It does this itself, not the runner, so every runner gets it; an attempt on another host stages it again from storage.
- A URL input is never in `StartSession`: it's only `SESSION_INPUT_URL` in `SessionSpec.env`, and the job fetches it inside its sandbox, where private networks are blocked. The agent runs outside the sandbox and could reach the host's home network, so it never fetches a renter's URL.

**Logs** (ADR 0019)
- The agent uploads the job's output in chunks through `attempt_grant`, every 5 seconds or every 64 KiB, at most 10 MiB per attempt, and never lets a log upload block, slow or fail the job.
- `RunningSession.latest_log_seq` (in heartbeats) and `SessionStatus.latest_log_seq` report the highest chunk uploaded. The control plane keeps it per attempt, only ever raises it, and reads only the chunks it names, so it never lists storage. The last chunk is flushed before the terminal status is sent.

**Downloads**
- The input is read through `read_grant`, not a separate URL. The read grant is needed for resume anyway, so each job uses one storage mechanism, not two.
- `registry_token` stays a plain token, because it's used once, to pull the image when the attempt starts.

