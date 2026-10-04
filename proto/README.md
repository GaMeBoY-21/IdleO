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

It runs only when `proto/**` changes.

### Code generation notes

No generated code is committed. Each side generates its own when it's built, with default settings. The stream is named `OpenStream` rather than `Connect`, so it doesn't collide with the `connect()` constructor that tonic generates on every Rust client.

## Compatibility rules

- **Additive only.** Add new fields, messages, enum values and RPCs. Never change a field's type, name, number or meaning.
- **Removed fields are `reserved`**, both number and name, so they can never come back with another meaning.
- **Never renumber** fields or enum values.
- **The last three agent releases stay supported** by the control plane. `Welcome.minimum_agent_version` and `UpdateAgent` move older agents forward.
- **`buf breaking` with `FILE` rules** guards all of this in CI.

## Delivery and acks

- **`message_id`**, on every envelope, identifies one delivery, as a UUIDv7. A retry reuses it, so the receiver drops duplicates.
- **`command_id`**, on `StartSession` and `StopSession`, is business idempotency. It survives reconnects and outbox re-sends, and a repeated command is acked and never run twice.
- **`Ack.acked_message_id`** names the envelope being acked. An ack means "received and durably recorded", not "accepted". The sender keeps its retry copy until the ack arrives. Acks are never acked.

| Message | Direction | Acked? | Why |
| --- | --- | --- | --- |
| `Hello` | agent → control plane | No | Answered by `Welcome` |
| `Welcome` | control plane → agent | No | Sent again on every new stream |
| `Heartbeat` | agent → control plane | No | The next one is 5 seconds away |
| `Offer` | control plane → agent | No | Answered by `OfferReply` |
| `OfferReply` | agent → control plane | Yes | |
| `StartSession` | control plane → agent | Yes | |
| `LeaseRenew` | control plane → agent | No | The lease survives three missed renewals |
| `SessionStatus` | agent → control plane | Yes | Kept on disk until acked |
| `UsageReport` | agent → control plane | Yes | Kept on disk until acked |
| `StopSession` | control plane → agent | Yes | |
| `UpdateAgent` | control plane → agent | Yes | |
| `RefreshCredentials` | control plane → agent | Yes | |
| `Ack` | both | No | Acks are never acked |

## Time and attempts

- **No message carries an absolute time for the agent to act on.**
  - Spans are `google.protobuf.Duration`.
  - The lease is `lease_ttl`: the agent's deadline is its lease clock at receipt plus `lease_ttl`, so it can only stop early, never late. The lease clock keeps counting while the machine sleeps; see below.
  - The one `Timestamp`, `IssuedCertificate.not_after`, mirrors the certificate's own expiry, for display only.
- **Every session-scoped message carries `attempt`.** The control plane acks and then ignores any message whose attempt isn't the current one.
- **`StartSession.minute_index_base`** maps this attempt's first minute onto the session-wide minute index.

## The lease clock

A laptop that sleeps must not run past its lease. Say a lid closes mid-job, the control plane re-matches the job elsewhere, and the laptop wakes 20 minutes later. A clock that paused during sleep would let the old attempt keep running beside the new one. So the agent follows these rules. The agent itself is built later.

**1. Deadlines use a clock that keeps counting while the machine sleeps.**
- **Linux:** `clock_gettime(CLOCK_BOOTTIME)`. The man page says it "also includes any time that the system is suspended". `CLOCK_MONOTONIC` "does not count time that the system is suspended", so it's never used for deadlines.
- **Windows:** `QueryPerformanceCounter`. Microsoft's documentation says it returns the ticks since Windows started, "including the time when the machine was in a sleep state such as standby, hibernate, or connected standby". `QueryUnbiasedInterruptTime` excludes sleep, so it's never used.
- **Not Rust's `std::time::Instant`.**
  - It uses `CLOCK_MONOTONIC` on Linux, which stops during suspend, and `QueryPerformanceCounter` on Windows.
  - Rust's documentation says whether suspends count "varies across platforms and Rust versions".
  - So the agent calls the OS clock directly.
- **On Windows hosts, the clock is read in the agent's Windows process, never inside the WSL2 VM.** Nothing documents that the VM's clocks count the host's sleep.

**2. On wake, check every deadline first.** When the OS reports a resume, the agent checks every deadline before doing anything else. It stops each attempt whose deadline has passed.

**3. Deadlines survive a process restart, but never a reboot.**
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

## Storage layout

Jobs move data through S3-compatible object storage (R2 today). There are no pre-signed URLs. Each attempt gets short-lived credentials (`StorageGrant`), scoped to prefixes and enforced by storage, and the control plane refreshes them with `RefreshCredentials`. One bucket holds:

```
sessions/{session_id}/
  input/                     the job's input; agents can only read it
  attempt-{n}/               one folder per attempt; only attempt n can write here
    checkpoints/
      {seq}/                 one folder per checkpoint, seq = 1, 2, 3 ...,
        ...                  written as 8 zero-padded digits (00000001)
        _COMPLETE            completion marker, written last
    outputs/
      ...
      _COMPLETE              completion marker, written last
```

### Grants

Each attempt gets exactly two grants, inside `Credentials`:

| Grant | Permission | Prefixes |
| --- | --- | --- |
| `attempt_grant` | read-write | `sessions/{session_id}/attempt-{n}/` |
| `read_grant` | read-only | `sessions/{session_id}/input/` and the folder of `StartSession.resume_from`, if any |

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

**Downloads**
- The input is read through `read_grant`, not a separate URL. The read grant is needed for resume anyway, so each job uses one storage mechanism, not two.
- `registry_token` stays a plain token, because it's used once, to pull the image when the attempt starts.

