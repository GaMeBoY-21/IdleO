# 0011. The agent stream: mutual TLS, Hello and Welcome, heartbeats and presence

- **Status:** Accepted; the step where the owner types a one-time code is superseded for hosts by ADR 0032 (linking by approval). The code stays for operators and tests.
- **Date:** 2026-10-05

Sources:
- docs/plan/technical-design.md lines 24, 34, 64, 81, 97, 241–267, 352, 567, 716, 719, 784, 824
- ADRs 0007 and 0010
- docs/prompts/agent-gateway-stream.md

## Context

Hosts can enrol and get a client certificate (ADR 0010). Every later feature (offers, sessions, leases, usage) travels over the agent's one live stream, `AgentGatewayService.OpenStream`. This records how that stream is authenticated, how it starts, and how the control plane knows which hosts are online.

## Decision

### Mutual TLS that only our CA can pass

1. **The gRPC port's trust store holds our root only, never the JDK's default `cacerts`.**
   - With a default trust manager, any public-CA client certificate would pass the handshake. The fingerprint lookup would still refuse it, but it should never get that far.
   - A normal TLS client doesn't even offer a certificate from a CA the server doesn't list. One that forces a foreign certificate fails the handshake.
   - A test checks that the CA names the server advertises are exactly our root's. It fails if the trust store is switched to the JDK default.
2. **An interceptor on `AgentGatewayService`, and only there**, reads the client certificate from the TLS session and asks the hosts module (`HostCertificates.authenticate(fingerprint)`) which host it belongs to. The host is accepted only if all of these hold:
   - the certificate row exists and isn't revoked;
   - now, from the injected clock, is inside its validity;
   - the host isn't suspended.

   As defence in depth, the certificate's single `urn:idleo:host:` name must be that same host.
   - **No certificate:** `UNAUTHENTICATED`.
   - **Unknown, revoked, expired or mismatched:** `UNAUTHENTICATED`, with one shared message.
   - **A suspended host:** `PERMISSION_DENIED`.

   The host id goes into the gRPC `Context`. Nothing in a message identifies the caller (ADR 0007).
3. **`EnrollmentService` stays open without a client certificate**, on the same port. Spring gRPC's security layer permits exactly `Enroll`, `OpenStream` and `RenewCertificate`, and denies everything else.

### Renewal

4. **`RenewCertificate` is allowed only in a certificate's last 10 days** (renew after 20 of 30, line 784). Earlier gets `FAILED_PRECONDITION` with the time renewal opens, which stops a broken or hostile agent from minting certificates in a loop.
   - The CSR rules and the issuing code are enrolment's (one `CertificateIssuer`).
   - The old certificate stays valid until it expires, and both are recorded in `hosts.host_certificates`.
   - **Only from the newest certificate.** If the host already has a newer, unrevoked certificate, renewal gets `FAILED_PRECONDITION` ("This certificate was already renewed; use the newer certificate."). The calling certificate's row is locked for the check (`select … for update`), so two concurrent renewals from it yield exactly one new certificate. Together with the 10-day window, a host can hold at most two valid certificates at once, and a leaked old key can't mint a fresh one after the agent has renewed.

### The stream

5. **Hello first.** The first message must be `Hello` within 10 seconds. Anything else first, or nothing in time, closes the stream with `INVALID_ARGUMENT` or `DEADLINE_EXCEEDED`.
   - `Hello` must have a semantic `agent_version`, an OS, and at least one GPU, each with a uuid, VRAM above zero, and no repeats. A bad `Hello` closes the stream with `INVALID_ARGUMENT`, and the reason is logged with the host id.
   - A valid `Hello` is recorded through the hosts module (`HostAgentReports`): the host's OS and agent version, and its GPUs, upserted by uuid into `hosts.host_gpus` (V4). A GPU missing from a later `Hello` is marked gone, not deleted.
6. **Welcome** carries:
   - the heartbeat interval (5 seconds), the temperature cap (85) and the session limit (1), from config until host listings set them per host;
   - `minimum_agent_version`.

   An older agent is still welcomed, but marked `update_required` in presence, so the scheduler can skip it. Every message the server sends gets a UUIDv7 `message_id`.
7. **Messages not handled yet** are counted in `agents.messages.unhandled` and dropped. They're never acked, and never a crash. They arrive with their features (see the ack table in `proto/README.md`).
8. **One stream per host.** A second connection for the same host replaces the first: the old stream closes with `ABORTED` ("Replaced by a newer connection").
   - Every presence write is conditional on the connection that owns the record. So the old stream's cleanup (a compare-and-delete Lua script) and its late heartbeats (compare-and-set) never touch the newer stream's record.
   - If the newer stream is on another instance, the old one finds out at its next heartbeat and closes itself.

### Presence in Valkey

9. **Valkey arrives now, with its first user** (lines 34 and 81). It's Valkey 9, the newest major DigitalOcean's managed service offers ([DigitalOcean docs](https://docs.digitalocean.com/products/databases/valkey/how-to/create/): "We support Valkey versions 8 and 9. New clusters use Valkey 9 by default").
   - It's pinned as `valkey/valkey:9.1.2@sha256:418652cfb58ef879d4978c33553735d7147016032d5aefaa14c828e611eb9dfd`, the same in `compose.yaml` and Testcontainers; a test keeps them identical.
   - Spring Boot connects to it as Redis (Lettuce), with a 1-second timeout.
10. **One hash per host**, `agentgateway:presence:<host-id>`, holding:
    - the connection and instance ids (each process has a stable instance id, for command routing later, line 34);
    - connected-at, agent version, `update_required`, `owner_active`, `net_up_kbps`;
    - the latest per-GPU readings.

    It lives for 15 seconds after the last heartbeat (line 352, "heartbeat within the last 15 seconds"). Only the agent gateway writes presence.
11. **Heartbeats are validated.** Each GPU uuid must be one this host reported in `Hello`. Temperature must be 0–150, utilisation 0–100, and VRAM, kbps and power non-negative; `power_w` is optional. An invalid heartbeat is dropped and counted, and the stream stays open.
12. **`last_seen_at`** in PostgreSQL is written at most once a minute per host, never per heartbeat.
13. **Valkey stays out of readiness.** If Valkey is down, agents stay connected, heartbeats are counted as `not_recorded`, and the problem raises an alert. Pulling every instance out of the load balancer would drop every agent at once, a far worse failure than stale presence. Presence rebuilds from the next heartbeats (line 81).

### Keepalive

14. **Settings on our own gRPC server factory**, which bypasses Spring Boot's keepalive properties (ADR 0010):

| Setting | Value | Why |
| --- | --- | --- |
| Keepalive time | 60 s | The server pings a connection that's been idle a minute: well inside the shortest carrier-grade NAT idle timeouts (a few minutes), far inside [RFC 5382](https://www.rfc-editor.org/rfc/rfc5382)'s recommended 2 h 4 min for home routers |
| Keepalive timeout | 20 s | A peer that doesn't answer a ping in 20 s is gone; its stream ends and presence expires |
| Permit keepalive time | 20 s | Agents may ping as often as every 20 s (they use 30 s) without being sent `ENHANCE_YOUR_CALM` |
| Permit keepalive without calls | yes | An agent between streams keeps its NAT mapping and connection |

    While a stream is open, the agent's 5-second heartbeats keep the mapping alive anyway. The pings cover gaps and find half-open connections, so agents stay connected for hours behind home routers.

### Metrics

15. **Micrometer meters:**
    - `agents.connected` (gauge)
    - `agents.connect.refused` by reason
    - `agents.heartbeats` by outcome (recorded or not recorded)
    - `agents.heartbeats.invalid` by reason
    - `agents.streams.closed` by reason
    - `agents.messages.unhandled` by type

    The hosts-online alert (line 824) reads these. The actuator stays closed except the probes, and the export path comes with staging.

## Alternatives considered

- **Spring gRPC's X.509 pre-authentication:** not used. Our identity is the certificate's fingerprint row plus its URN, not a subject DN, and a small interceptor states the rule plainly.
- **Presence in PostgreSQL:** rejected. Writing a heartbeat every 5 seconds per host is the wrong load for the money database; presence may expire and rebuild.
- **Valkey in readiness:** rejected, for the reason in item 13.
- **Pub/sub to tell another instance its stream was replaced:** not yet. The conditional heartbeat write already closes a replaced stream within one heartbeat; command routing will add the instance channel.

## Consequences

- **Production must provide `VALKEY_URL`.**
- **Agents must handle the stream rules:** Hello within 10 seconds, heartbeats every 5, renewal in the last 10 days, and a reconnect after `ABORTED`.
- **The scheduler can find live hosts** in Valkey once it exists, and can skip `update_required` ones.
