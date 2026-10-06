# 0010. Host enrolment and our own certificate authority

- **Status:** Accepted
- **Date:** 2026-10-05

Sources:
- docs/plan/technical-design.md lines 27, 64, 96–98, 224, 540, 567, 572, 635–642, 758, 768
- `proto/gpu/agent/v1/enrollment.proto`
- ADRs 0003, 0007, 0008, 0009
- docs/prompts/hosts-and-enrolment.md

## Context

A host has to exist, and belong to a person, before its agent can connect. The agent then needs a client certificate for mutual TLS (technical design line 64). The agent protocol's `EnrollmentService.Enroll` trades a one-time code and a CSR for that certificate (ADR 0007). This records how hosts, codes, certificates and the gRPC transport are built.

## Decision

### Hosts and the API

1. **`hosts.hosts`** has `id` (UUIDv7), `owner_user_id`, `name`, `status` and timestamps.
   - New hosts are `pending`; the statuses are pending, verified, active, suspended (line 96). Enrolment doesn't change the status, because the benchmark and canary steps of onboarding move it on later.
   - There's no foreign key into `identity`, because modules don't reach into each other's tables. The owner is the `CurrentUser` id.
   - There's no `overlay_node_id`, `score`, `region`, `os` or `agent_version` yet, because nothing writes them.
2. **`hosts.host_certificates`** is one row per issued certificate: serial, SHA-256 fingerprint, validity, `revoked_at`. It's a table rather than a `cert_fingerprint` column, because renewal (after 20 of 30 days) overlaps two valid certificates.
3. **Creating a host and issuing its code are separate calls.**
   - `POST /v1/hosts` creates the host.
   - `POST /v1/hosts/{id}/enrolment-code` issues a code, and the latest replaces any earlier one.

   This changes line 567, which had `POST /v1/hosts` return the code. Split, the code is never stored to replay an idempotent response. The code response carries `Cache-Control: no-store`.
4. **Owner-only routes.** Another user's host is a 404, not a 403, so ids can't be probed.

### Idempotency-Key

5. **`POST /v1/hosts` requires an `Idempotency-Key`**, following the IETF draft [draft-ietf-httpapi-idempotency-key-header-07](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/). The header is a Structured Field String; a bare token is accepted too.
   - A missing or malformed key gets 400.
   - The same key with the same body returns the stored response, byte for byte.
   - The same key with a different body gets 422.
   - A request while the first is still running gets 409.
   - Keys are scoped to the user and expire after 24 hours.

   The request's fingerprint is the SHA-256 of its parsed body serialised as JSON. The action and the stored response commit in one transaction. If the action fails, the key is released. A key whose request vanished (a crash between claiming it and finishing) is taken over after one minute, so it can't block the client for a day.
6. **One shared helper, a table per module.** The helper is `IdempotentRequests` in the shared kernel, and each module keeps its own `<schema>.idempotency_keys` table (`hosts.idempotency_keys` here), so no module reads another's.

### Enrolment codes

7. **Format.** 100 bits from `SecureRandom`, as 20 [Crockford base32](https://www.crockford.com/base32.html) symbols, shown as `XXXXX-XXXXX-XXXXX-XXXXX`.
   - The alphabet has no I, L, O or U.
   - Decoding ignores case, hyphens and spaces, and maps O to 0, and I and L to 1, so a person can copy a code into the installer without mistakes.
8. **Storage.** Only `SHA-256(code)` is stored, in `hosts.enrolment_codes`, one live code per host. A code is never logged, and the database never holds it in plaintext (both are tested).
9. **Lifetime.** A code expires after 1 hour and works once. `used_at` is set by a single conditional update, in the same transaction that records the certificate. So two racing enrolments get exactly one certificate, and a crash can't leave a used code without a recorded certificate, or the other way round.

### Our certificate authority

10. **Structure (line 768).** An offline root, and an online intermediate that signs everything and lives in the secret store.
    - The intermediate's certificate, encrypted key and password, and the root's certificate, come from the environment only. The root's key never reaches the server.
    - At startup the control plane checks that the intermediate is a CA, that its key matches its certificate, and that the root signed it.
11. **Library.** [BouncyCastle](https://www.bouncycastle.org/) `bcpkix-jdk18on` 1.86. The CA lives in the hosts module's internal package, because hosts own which certificate belongs to which host. Only the hosts module issues or reads certificates.
12. **Algorithm.** ECDSA P-256 for the root, the intermediate, the server and agent keys, signed with SHA-256: it's small and fast, and both Java's TLS and rustls support it.
13. **The host certificate profile:**
    - 30 days
    - a random 127-bit serial (at least 64 bits)
    - `basicConstraints` critical `CA:false`
    - key usage critical `digitalSignature`
    - extended key usage `clientAuth` only
    - an empty subject, and the host's identity as the single URI SAN `urn:idleo:host:<host-id>`, marked critical because the subject is empty (RFC 5280 §4.2.1.6)
14. **What's taken from the CSR: only its public key.**
    - Every subject, SAN or extension the CSR asks for is ignored, and the CA writes the identity itself. A CSR asking for another host's URN still gets this code's host (tested).
    - The CSR's signature must verify, which proves the agent holds the key.
    - Only EC P-256 keys are accepted; RSA and other curves are refused.
15. **The server certificate.** The gRPC server's own certificate is also issued by our intermediate, with `serverAuth` only. Agents trust our root only, not public CAs, so no public CA can ever impersonate the control plane to an agent.
16. **The load balancer passes TLS through untouched (TCP mode).** Terminating TLS there would break mutual TLS for the agent stream.

### gRPC in the control plane

17. **The server: Spring Boot 4.1's own gRPC support.**
    - It's [`spring-boot-starter-grpc-server`](https://docs.spring.io/spring-boot/reference/io/grpc.html) 4.1.1, GA and built on Spring gRPC 1.1.1, with grpc-java 1.83.1 on Netty, all versions managed by Spring Boot.
    - Services are `@GrpcService` beans, and shutdown is graceful (30-second grace period).
    - Reflection and the gRPC health service are off, because nothing needs them.
18. **One TLS port** (9443 behind the load balancer's 443), **TLS 1.3 only**, with client certificates **requested but optional**.
    - `EnrollmentService` needs none. `AgentGatewayService` will require one through an interceptor.
    - gRPC security denies by default: `Enroll` is open and every other method is refused until it gets its own rule.
    - Spring gRPC 1.1 builds server TLS from the SSL bundle's key and trust managers but drops the bundle's protocol options; a TLS 1.2 client could connect, and a test caught it. So the agent gateway defines the gRPC server factory itself, building the Netty TLS context from the same bundle with its `enabled-protocols`. Spring Boot's own Netty server configuration then backs off. Service discovery, global interceptors and the lifecycle are still Spring gRPC's.
19. **Where the code lives.** The transport is in `agentgateway` (line 541). It calls the public `hosts` API, `HostEnrolment.enrol(code, csrDer)`.
    - **Unknown, malformed, used or expired codes** all get `UNAUTHENTICATED`, with the same message.
    - **A bad CSR or an empty field** gets `INVALID_ARGUMENT`.
    - **The reply** is the leaf, then the intermediate (never the root), plus `not_after`.
20. **Code generation.** The Maven build generates Java from `../proto` with [`io.github.ascopes:protobuf-maven-plugin`](https://ascopes.github.io/protobuf-maven-plugin/) 5.1.8.
    - Spring Boot's parent pins the plugin, `protoc` at protobuf-java 4.35.1 (which the plugin downloads itself, so a `protoc` on PATH is never used), and the grpc-java code generator.
    - Nothing generated is committed. The Java package is `gpu.agent.v1` until buf managed mode sets language options (ADR 0007).
    - The control-plane CI now runs on `proto/**` changes too.

### Local runs

21. **The development PKI.** `scripts/dev-ca.sh` creates a throwaway root, intermediate and `localhost` server certificate under the gitignored `private/dev-ca/`, with the same shape as production. The `local` profile reads it from there; that's the one place a CA path isn't an environment variable, and it's development only.

## Alternatives considered

- **A public CA, or a managed private CA service:** rejected. Agents must trust only us, and our CA writes the host identity itself. A managed CA can replace the intermediate's key storage later without changing the profile.
- **RSA keys:** rejected. They're larger and slower, and P-256 is supported everywhere we need.
- **Returning the code from `POST /v1/hosts`:** rejected. An idempotent replay would have to store the code.
- **Grpc-java's Netty server as a bean of our own:** not needed. Spring Boot 4.1's GA gRPC support covers it, apart from the TLS protocol gap in item 18.
- **A CRL or OCSP:** not now. Revocation is the `revoked_at` check at connect, with the agent stream.

## Consequences

- An agent with only a code and a fresh key pair gets a 30-day certificate chained to our root. The end-to-end test takes about 340 ms, from `POST /v1/hosts` to the certificate in hand.
- **Because we define the gRPC server factory ourselves,** Spring Boot's property-driven gRPC server builder settings (for example keep-alive) don't apply. Any we need are added as `ServerBuilderCustomizer` beans.
- **Production must provide** the root certificate, the intermediate certificate, key and password, and the gRPC server certificate and key, all from the secret store (`docs/runbooks/certificate-authority.md`).
- **Converging gRPC's Google libraries** pins Guava `33.6.0-jre` and `error_prone_annotations` 2.50.0 in the control plane's dependency management.
