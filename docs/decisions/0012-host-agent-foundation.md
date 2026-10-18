# 0012. The host agent's foundation: workspace, trust, reconnects and key storage

- **Status:** Accepted
- **Date:** 2026-10-06

Sources:
- docs/plan/technical-design.md lines 27, 64, 244, 262, 474–487, 784
- ADRs 0007, 0010 and 0011
- docs/prompts/agent-connect.md

## Context

The control plane can enrol hosts (ADR 0010) and hold their streams (ADR 0011). Now the agent needs its first two jobs from the connection manager row (line 478): enrol with a one-time code, then hold one mutual-TLS stream with heartbeats, reconnects and certificate renewal. It runs on other people's Windows and Linux machines, so its supply chain, its trust rules and how it keeps its private key matter from the first line.

## Decision

### Workspace

1. **A Cargo workspace in `agent/` with three crates:**
   - `agent-proto`: generated code only, from `proto/gpu/agent/v1`, built at compile time and never committed.
   - `agent`: the library `idleo_agent` and the binary `idleo-agent`.
   - `agent-windows`: the only `unsafe` code (item 13).

   The toolchain is pinned in `rust-toolchain.toml` (1.99.0, with rustfmt and clippy), and every dependency in the committed `Cargo.lock`. Clippy runs with `pedantic` and `-D warnings`; an individual lint is allowed only with a comment saying why.
2. **`protox` instead of `protoc`.** `agent-proto/build.rs` compiles the .proto files with protox, a pure-Rust protobuf compiler, and hands the descriptors to `tonic-prost-build`. No `protoc` on the machine is ever used: Nikki's Anaconda `protoc` can't leak in, and CI needs no protobuf install. The build passes with `PROTOC` pointing at nothing and no `protoc` on `PATH`.
3. **`cargo deny check`** runs in CI with a committed `deny.toml`:
   - permissive licences only (Apache-2.0, MIT, BSD-3-Clause, ISC, Zlib, Unicode-3.0), so no GPL, LGPL or AGPL;
   - every RustSec advisory, including unmaintained crates, fails the build;
   - crates.io only.

   It checks every shipped target (Windows, Linux x86-64 and ARM64, macOS), not just the runner's.

### TLS and trust

4. **rustls with the `ring` provider.** ring builds with only a C compiler on all three systems, is smaller, and is the library rcgen already uses, so the agent carries one crypto library, not two.
   - **Given up:** aws-lc-rs's post-quantum hybrid key exchange and its FIPS option. Switching is a feature flag on rustls, tokio-rustls and rcgen.
5. **TLS 1.3 only, and only our root.**
   - The agent builds its own rustls config: TLS 1.3 only; trust anchors only from `ca_root`, with no system store and no webpki roots; HTTP/2 by ALPN.
   - It dials through its own connector under a plain-HTTP tonic endpoint, so tonic adds no roots or protocol versions of its own. TLS 1.2 isn't even compiled into release builds.
   - A server certificate from another CA fails the handshake, before any code or CSR is sent. Tests cover a foreign server certificate (for both enrolment and the stream) and a TLS 1.2-only server.
6. **Issued certificates are checked before anything is saved**, for both `Enroll` and `RenewCertificate`:
   - the leaf chains to `ca_root` through the returned intermediate, is valid now, and is for client authentication (rustls's own WebPKI client verifier);
   - its public key is the one we just generated;
   - its subject alternative names are exactly one `urn:idleo:host:<uuid>`.

   A renewed certificate must also name the same host.

### Reconnects (line 262)

7. **Backoff:** the ceiling starts at 1 second and doubles to a 60-second cap. Each delay is uniformly random below the ceiling (full jitter), so agents that lost the control plane together come back spread out.
8. **Reset only after 60 seconds healthy after `Welcome`.** A server that accepts and immediately drops would otherwise reset every agent's backoff on each attempt, and they would hammer it at 1-second intervals. A test shows 30 seconds up continuing the sequence (4 then 8), and 61 seconds up starting it again at 1.
9. **How each ending is handled:**

| Ending | Retry |
| --- | --- |
| Network or TLS failure, `UNAVAILABLE`, any other status, no `Welcome` within 10 s, an invalid `Welcome` (missing or zero heartbeat interval, ADR 0007) | Normal backoff |
| `ABORTED` (replaced by a newer connection) | Normal backoff, plus a warning that another machine may be using this certificate |
| `UNAUTHENTICATED` or `PERMISSION_DENIED` (revoked certificate or suspended host) | Slow retry: 10 minutes plus up to 1 minute of jitter. The log says to re-enrol or contact support, and a reinstated host recovers by itself |
| The certificate has expired (checked locally before dialing) | Slow retry, and the log says the host needs a new enrolment code |

10. **Renewal:** checked after the first `Welcome` and every 6 hours.
    - With 10 days or fewer left (renew after 20 of 30 days, line 784), the agent generates a new key pair and CSR and calls `RenewCertificate` over the current mutual-TLS connection.
    - It checks the reply as in item 6, saves it atomically, and reconnects at once with the new certificate. The reconnect is not a failure, so there's no backoff.
    - `FAILED_PRECONDITION` (too early, or already renewed; ADR 0011) waits for the next check.
11. **Shutdown:** Ctrl-C, SIGTERM, or a Windows console close or shutdown half-closes the stream and waits up to 3 seconds for the control plane to finish it. A hard limit exits at 5 seconds.

The test suite runs the backoff tests on tokio's paused clock. They use an in-memory pipe in place of TCP, with the same TLS 1.3, client certificates and tonic server. Real sockets let a paused clock jump ahead while waiting on I/O, which would fire timeouts that never happen in real time.

### Key storage

12. **`data_dir` holds `host.key` (PKCS#8 PEM), `host.crt` (leaf, then intermediate) and `host.json` (host id and expiry).**
    - **Replaced as one set:** new files are staged as `*.new` and flushed, and a complete `host.json.new` is the commit point. Each file is then renamed into place, `host.json` last.
    - **Crash recovery:** at the next start, an interrupted save is rolled forward if it passed the commit point and discarded if it didn't. A crash never leaves half a key, or a key that doesn't match its certificate. On load, the agent checks that the key matches the certificate and that `host.json` names the certificate's host.
    - **`enrol --force`** moves the old set to `backup-<time>/` only after the new certificate has passed its checks.
13. **Per OS:**
    - **Unix:** `host.key` is created with mode 0600 (the directory 0700). The agent refuses to start if the key is readable or writable by group or others, and says to run `chmod 600`.
    - **Windows:** `host.key` is created by `CreateFileW` with an access list allowing only SYSTEM and Administrators (`D:P(A;;FA;;;SY)(A;;FA;;;BA)`, protected from inheritance). The list is applied at creation, so no other account ever has a moment to open the file. This is in `agent-windows`, the one crate with `unsafe`: `#![deny(unsafe_code)]` with a single allowed module, where every `unsafe` block states why it is sound. A test on the Windows runner reads the list back. Because of it, the agent on Windows runs elevated, or later as a service. *Updated by ADR 0033:* the app now installs per user and never runs elevated, so the list is SYSTEM plus the signed-in owner's own SID, read at run time (`D:P(A;;FA;;;SY)(A;;FA;;;<owner SID>)`, `agent_windows::private_file_sddl`); Administrators is no longer listed, and the data folder is `%LOCALAPPDATA%\IdleO\agent`, not `%ProgramData%\IdleO\agent`.
    - **Everywhere else** `#![forbid(unsafe_code)]` holds, crate-wide in `agent` and `agent-proto`.
    - **Never logged:** the key, CSRs and enrolment codes. The code type hides itself in `Debug`, and a test captures every trace-level log line in the test process and checks that no fragment of either key, any CSR or any code appears.

### Telemetry and the owner

14. **`GpuSource`** has two implementations:
    - **`nvml`:** NVML through `nvml-wrapper`, loaded at run time from the driver. It's behind the `nvml` feature, which is on by default and present only in Windows and Linux builds. If NVML is missing or finds no GPU, `run` exits with code 7 and says why.
    - **Fake:** `--fake-gpu [N]` gives invented GPUs whose uuids are plainly fake (`GPU-00000000-fake-…`).

    The agent never falls back to fake GPUs by itself. GPUs are read every second, and each heartbeat carries per GPU the highest temperature and power, the mean utilisation and the latest memory in use (line 479). `net_up_kbps` is 0 until the onboarding benchmark exists.
15. **`OwnerActivity`:** on Windows, keyboard or mouse input within 2 minutes (`GetLastInputInfo`). On Linux and macOS, always false for now.

### Deliberately not built yet

The lease clock, the local store (SQLite), the session runner, the cache manager, the policy enforcer, the watchdog, the updater, and installing as a Windows service or systemd unit (lines 474–487). The connection manager doesn't yet keep outgoing messages until they're acknowledged, because nothing it sends is acked: Hello and Heartbeat aren't, and the acked messages arrive with sessions.

## Alternatives considered

- **`protoc` from the system, or `protoc-bin-vendored`:** rejected. The system one varies by machine (and Anaconda's shadows Homebrew's); the vendored one is a downloaded binary per platform. protox is a crate like any other, checked by cargo deny.
- **aws-lc-rs:** not now; see item 4.
- **tonic's built-in TLS:** rejected. It allows TLS 1.2 and can add system or webpki roots through features; our own connector makes "TLS 1.3, our root only" impossible to loosen by accident.
- **Constant or equal-jitter backoff:** rejected. Full jitter spreads a thundering herd best, and the cap keeps the worst wait at a minute.
- **Resetting the backoff on every successful connect:** rejected, for the reason in item 8.
- **One key-and-certificate file, or a database:** rejected. Three plain files are easy to inspect and back up, and the staged rename gives the same all-or-nothing replacement.
- **`#![forbid(unsafe_code)]` with an `allow` in one module:** impossible, because `forbid` can't be overridden. Hence the separate `agent-windows` crate.

## Consequences

- **Agents fail closed on trust.** No public CA, corporate proxy or TLS 1.2 middlebox can stand between an agent and the control plane. A network that intercepts TLS can't run an agent.
- **The HTTP/2 `:scheme` is `http` inside our TLS,** because tonic refuses `https` with a custom connector. gRPC servers generally ignore it; the first run against the Java control plane (next piece of work) confirms it.
- **On Windows the agent needs SYSTEM or Administrator rights** to read its key. That's how it will run as a service anyway.
- **A Mac sends `OPERATING_SYSTEM_MACOS`** (closed by ADR 0013). The protocol gained that development-only value, and the control plane accepts it, like fake GPUs, only with `agentgateway.allow-development-hosts`, which the `prod` profile refuses.
- **Owner activity needs a helper once the agent is a Windows service.** `GetLastInputInfo` sees only its own session, and a service runs in session 0, which never sees keyboard or mouse input. So the service will need a small helper running in the signed-in user's session to report input, or the WTS session APIs. It's built with the installer and service registration, not now; until then the agent runs in the user's session and reads input directly.
