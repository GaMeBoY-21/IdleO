# proto

The shared .proto files that define the agent protocol, compiled for both Rust and Java and checked by buf.

Starts in Phase 1 (compute core), with host agent v0 and the control plane it talks to.

## Layout

A buf v2 module. `buf.yaml` sits here, and package `gpu.agent.v1` sits in `gpu/agent/v1/`:

| File | What it holds |
| --- | --- |
| `agent_gateway.proto` | `AgentGatewayService` (`Connect` stream, `RenewCertificate`); the `ConnectRequest` and `ConnectResponse` envelopes; `Hello`, `Welcome`, `Ack`, `UpdateAgent` |
| `session.proto` | `SessionSpec`, `Credentials`, session states; `Offer`, `OfferReply`, `StartSession`, `LeaseRenew`, `StopSession`, `SessionStatus`, `UsageReport`; `RunningSession`, the one snapshot type shared by `Hello` and `Heartbeat` |
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

No generated code is committed. Each side generates its own when it's built.

**Rust (tonic) must disable the transport helper:**
```rust
tonic_prost_build::configure()
    .build_transport(false)
    .compile_protos(...)
```
Our RPC is named `Connect`, and tonic's generated `connect()` constructor collides with it (error E0592). The agent builds its client on its own mTLS channel with `AgentGatewayServiceClient::new(channel)`, so it never needs the helper.

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
| `Ack` | both | No | Acks are never acked |

## Time and attempts

- **No message carries an absolute time for the agent to act on.**
  - Spans are `google.protobuf.Duration`.
  - The lease is `lease_ttl`: the agent's deadline is its monotonic clock at receipt plus `lease_ttl`, so it can only stop early, never late.
  - The one `Timestamp`, `IssuedCertificate.not_after`, mirrors the certificate's own expiry, for display only.
- **Every session-scoped message carries `attempt`.** The control plane acks and then ignores any message whose attempt isn't the current one.
- **`StartSession.minute_index_base`** maps this attempt's first minute onto the session-wide minute index.
