# agent

The Rust host agent that runs on each host's laptop or PC, and the Windows app around it (ADRs 0012, 0033, 0034). The agent links the host by approval (or enrols it with a one-time code), keeps one mutual-TLS stream open to the control plane, sends heartbeats with GPU telemetry, runs sessions under leases, pauses by itself while the owner uses the laptop, self-tests, updates itself from the signed release feed, reconnects with capped backoff, and renews its certificate before it expires. On Windows it's one of five programs, installed per user by `IdleO-Setup.exe`.

## Layout

A Cargo workspace:

| Crate | What it is |
| --- | --- |
| `agent-proto` | Rust code generated from `../proto/gpu/agent/v1` at build time, and nothing else. Its `build.rs` uses `protox`, a pure-Rust protobuf compiler, so no `protoc` on the machine is ever used. Nothing generated is committed. |
| `agent` | The library (`idleo_agent`) and two binaries: `idleo-agent` (the command line) and `idleo-hostd` (its `run` with the Windows GUI subsystem, so no console window ever flashes; the same code). |
| `agent-windows` | The workspace's only `unsafe` code: Windows API calls for owner activity (input, full-screen apps, power), the OS build, virtualization, the key file's and the pipe's access lists, the per-user registry, shortcuts and the native dialogs. Empty on other systems. |
| `release` | The library (`idleo_release`) the agent, setup and the launcher share, one implementation each: the app's folders, the signed manifest and its key, download with resume and verify, versions, installing and removing the app, the WSL commands, the local channel and the update trial's state. Also the binary `idleo-release` (keys, manifests, signing, uploads), the only code that reads a private release key. |
| `launcher` | `idleo-launcher.exe`: small and rarely changed. Starts and watches `idleo-hostd` and `idleo-tray`, runs the update trial and rollback, and uninstalls. The Run entry, the Start-menu shortcut and the Uninstall entry point at it. |
| `tray` | `idleo-tray.exe`: the tray icon and its native menu (`tray-icon`, no webview). |
| `setup` | `IdleO-Setup.exe`: the one download, a small WebView2 window (`wry`) whose page is built into the program. |
| `brand` | The icon and the Windows manifest (`asInvoker`, per-monitor DPI) the four Windows programs embed. |

The toolchain is pinned in `rust-toolchain.toml` (with rustfmt and clippy), and every dependency version in the committed `Cargo.lock`.

## Build and check

Run from `agent/` (rustup installs the pinned toolchain on first use):

```
cargo build --release          # target/release/idleo-agent
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings    # pedantic is on for the workspace
cargo test --workspace
cargo deny check               # licences (permissive only, no GPL) and RustSec advisories
```

The tests start an in-process control plane (tonic, with a CA made by rcgen in the test) and talk to it over real TLS 1.3 with real client certificates. Backoff tests run on tokio's paused clock, over an in-memory pipe instead of TCP, so minutes of backoff take milliseconds.

## Run on a Mac with fake GPUs

macOS has no NVML, so use `--fake-gpu` (one invented GPU; `--fake-gpu 2` for two). Each reports itself as `IdleO Fake GPU` with 12,282 MB, unless given a laptop's: `--fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8188` (at least 1,024 MB; both only with `--fake-gpu`). Heartbeats report the data folder's real free disk unless `--fake-free-disk-mb MB` says otherwise (only with `--fake-gpu`), so a local stack can show a host without room for a session's files. Fake GPUs are only ever used when asked for: without the flag, an agent that can't load NVML exits with exit code 7.

1. Write `~/Library/Application Support/IdleO/agent/agent.toml` (or pass `--config <path>`):
   ```toml
   ca_root = "ca-root.pem"            # our root certificate, the only trust anchor
   log_format = "text"                # or "json"
   [control_plane]
   host = "localhost"
   port = 9443
   ```
   For a local control plane, `ca-root.pem` is the root from `control-plane/scripts/dev-ca.sh` (`private/dev-ca/`).
2. `idleo-agent link`: it opens the web app's approval page in the browser (or prints the link, on a machine with no browser), and you click **Approve**; no code to copy (ADR 0032). The config needs the control plane's REST address, `api_url = "http://localhost:8080"` (or `link --api URL`). Operators and tests can still enrol with a code: `idleo-agent enrol --code XXXXX-XXXXX-XXXXX-XXXXX`.
3. `idleo-agent run --fake-gpu`. Ctrl-C closes the stream within 5 seconds.

A Mac reports `OPERATING_SYSTEM_MACOS` in `Hello`. That value is for development only: a control plane accepts it, and fake GPUs, only with `agentgateway.allow-development-hosts` on, which the `local` profile sets and production refuses (ADR 0013).

## Config: `agent.toml`

| Key | Meaning | Default |
| --- | --- | --- |
| `control_plane.host`, `control_plane.port` | Where the agent dials; the host is also the name the server certificate must carry | required |
| `ca_root` | Our root certificate (PEM). The only trust anchor: no system or webpki roots | `ca-root.pem` next to the config file |
| `data_dir` | Where the identity lives | the default directory below |
| `log_format` | `text` or `json` | `text` |
| `welcome_timeout_secs` | How long to wait for `Welcome` after `Hello` | 10 |

Relative paths are relative to the config file. The default directory, for both `agent.toml` and the identity:
- **Windows:** `%LOCALAPPDATA%\IdleO\agent` (per user, ADR 0033)
- **Linux:** `/var/lib/idleo-agent`
- **macOS:** `~/Library/Application Support/IdleO/agent`, for development only

Logs go to stderr: the agent at info and libraries at warn, or as `IDLEO_LOG` says (for example `IDLEO_LOG=debug`, or `IDLEO_LOG=idleo_agent=debug,h2=info`). The key, CSRs and enrolment codes are never logged; a test captures trace-level logs and checks.

## On disk

In `data_dir`:
- `host.key`: the private key (ECDSA P-256, PKCS#8 PEM). Unix: mode 0600, and the agent refuses to start if it's more open. Windows: created with an access list allowing only SYSTEM and the signed-in owner's own SID, read at run time (ADR 0033), so the agent never needs to run elevated.
- `host.crt`: the host certificate, then the intermediate.
- `host.json`: the host id (from the certificate's `urn:idleo:host:` name) and the certificate's expiry.

- `work/`: each attempt's scratch folder, `work/{session}-{attempt}/`, holding its staged input. It's wiped when the attempt ends, and the whole of `work/` when the agent starts.

The three identity files are replaced together: new files are staged as `*.new`, `host.json.new` is the commit point, then each is renamed into place. A crash never leaves half a key; an interrupted save is finished or discarded on the next start. `enrol --force` moves the old files to `backup-<time>/` first.

## Commands and exit codes

- `idleo-agent link [--name NAME] [--api URL] [--no-browser] [--force] [--fake-gpu [N] [--fake-gpu-model NAME] [--fake-gpu-vram-mb MB]]` (ADR 0032): reads the machine's name and GPUs, asks `POST /v1/host-links`, opens the approve address in the default browser (`open`, `xdg-open` or `explorer.exe`; if none can be opened it prints "Open this link on any device to approve: URL"), and polls every 3 seconds, saying once "Waiting for you to approve in the browser…". When the owner approves it gets the host's one-time enrolment code, trades it for its certificate exactly as `enrol` does (the key pair is made here and never leaves), and prints "Linked to n… as 'name'. You can close the browser." Its exit codes are the table's below, with **0 linked, 2 denied, 3 expired, 4 network** and 5 already linked, 6 the identity couldn't be saved, 7 no GPU source. The flow is a library function, `idleo_agent::link::link`, which reports progress through a callback and prints nothing itself; the Windows setup app calls it and shows its own screens.
- `idleo-agent enrol --code <code> [--force]` (for operators and tests; a host's owner links with `link`): generates a key pair, sends the code and a CSR over TLS 1.3, checks the certificate (it chains to `ca_root`, it's for our key, its only name is one host URN), and only then saves it. A malformed code is refused before any network call.
- `idleo-agent run [--fake-gpu [N] [--fake-gpu-model NAME] [--fake-gpu-vram-mb MB] [--fake-free-disk-mb MB] [--dev-runner]]`: connects with mutual TLS, sends `Hello`, waits for `Welcome`, then sends a heartbeat every interval the control plane sets. GPUs are read every second; each heartbeat carries, per GPU, the highest temperature and power, the mean utilisation and the latest memory in use. `net_up_kbps` is 0 until the onboarding benchmark exists, `owner_active` is keyboard or mouse input in the last 2 minutes on Windows and always false on Linux and macOS for now, `sessions` lists the attempts running, and `free_disk_mb` is the free space on the data folder's volume, where sessions' files go (ADR 0022), absent when it can't be read: the scheduler offers a session only to a host with room for its inputs and scratch disk. `Hello` carries it too, so the host's room is known from the moment it connects.
  - Reconnects: backoff from 1 second doubling to 60, with full jitter, reset only after a connection stays up 60 seconds past `Welcome`. `UNAUTHENTICATED` or `PERMISSION_DENIED` (revoked certificate or suspended host) and an expired certificate retry every 10 minutes (plus up to 1 minute of jitter), so a reinstated host recovers by itself. `ABORTED` (another connection for this host replaced ours) is normal backoff with a warning.
  - Sessions (ADR 0016): see below.
  - Renewal: checked after the first `Welcome` and every 6 hours; with 10 days or fewer left, a new key pair and CSR go to `RenewCertificate` over the current connection, the reply gets the same checks as enrolment, and the agent reconnects with the new certificate. `FAILED_PRECONDITION` (too early, or already renewed) waits for the next check.

### Sessions

- **Runners.** An agent runs sessions only with a runner. The one runner today is the development runner, `--dev-runner`, which the agent refuses without `--fake-gpu`: it simulates a job for the `--duration` in the spec's arguments (`--duration 20s`), writing a checkpoint every `checkpoint_interval` and then its outputs, to real object storage through the session's grants. With `--simulate <kind>` in the arguments too (`fine-tune` or `run-code`), it plays that service on the local stack: the service's log lines as it runs (epochs, steps and a falling loss; print lines) and its outputs instead of `result.json` (a LoRA adapter in the safetensors format and a training log; `result.txt`), every byte generated from the parameters, the input's size and lines, and the session's id, never a real model. Its log is a schedule over the whole run, so a resumed attempt carries on where the last stopped. Without a runner the agent declines every offer (`INSUFFICIENT_RESOURCES`) and fails any `StartSession` with `no_runner`; it never pretends. The Docker runner, with the sandbox, comes with the Windows host.
- **StartSession** is acked once recorded. A repeated `command_id` is acked and never run twice. A malformed one is acked and reported `FAILED` with `invalid_start_session`.
- **Leases.** Each attempt's deadline is the lease clock at receipt plus `lease_ttl`; a `LeaseRenew` for the current attempt extends it, one for any other attempt is ignored. The lease clock counts while the machine sleeps: `CLOCK_BOOTTIME` on Linux, `QueryPerformanceCounter` on Windows, `CLOCK_MONOTONIC` on macOS (Apple: it "will continue to increment while the system is asleep"); never `std::time::Instant`. Deadlines are checked every 250 ms, connected or not, and before handling anything else, so on waking from sleep a lapsed attempt is stopped first. A lapsed attempt stops at once, is reported `LEASE_EXPIRED`, and never resumes. `max_runtime` is a hard cap the same way.
- **Stopping.** A `StopSession` with `checkpoint_first` asks the runner to write one last checkpoint and stop; the attempt is reported `STOPPED` once it has, or when the grace period runs out, whichever is first. Without `checkpoint_first` it stops at once. A repeated `command_id` is acked and acted on once.
- **Inputs** (ADR 0019, ADR 0022). Every uploaded file named in `StartSession.inputs` is staged by the supervisor, not the runner, into the attempt's `input/{slot}/{name}`, paths kept, while it's `PREPARING`, through the read grant, four at a time, and each size is checked. A file that fails 3 tries fails the attempt `input_unavailable`, before `RUNNING`, so nothing is billed. A link input is never fetched by the agent, which runs outside the sandbox: the job fetches `SESSION_INPUT_{NAME}_URL` itself, inside it. The development runner writes each staged file's path, size and SHA-256 by input, each link, every `SESSION_PARAM_*`, and for a `greeting` parameter the greeting repeated `repeat` times, into `outputs/result.json`.
- **Logs** (ADR 0019). Each attempt has its own log sink, independent of the runner: the runner writes bytes, and the sink uploads a chunk every 5 seconds or 64 KiB to `attempt-{n}/logs/{seq}.log` through the attempt grant, at most 10 MiB per attempt, then one line saying so. A failed upload waits for the next tick, in order; writing never waits on storage. Heartbeats carry each attempt's `latest_log_seq`, and the terminal status goes out only after the last chunk has flushed, or after 10 seconds.
- **Storage.** Every key is checked against the grant's prefixes (and read-only grants refuse writes) before any request; storage enforces the same scope. Grants are never logged. Requests are signed by `rusty-s3` and sent with `reqwest` over rustls (ring) and the operating system's trust store.
- **Usage** (ADR 0017): one `UsageReport` per whole minute of each attempt, counted on the lease clock from its first `RUNNING`; `minute_index` is `minute_index_base` plus the minute's count, `gpu_seconds` is 60, and `avg_util_pct` is the attempt's GPUs' mean utilisation over the minute. When the job ends, its last, partial minute goes with its real seconds (a started second counts). After a lease lapses, only the minutes that ended inside it are reported. The agent meters, not the runner.
- **Statuses** go out on every state change and checkpoint, kept until acked, resent every 5 seconds, and all resent on a new stream; usage reports share the queue. They're kept in memory: an agent restart loses them, and the control plane's lease handles that attempt. The persistent store comes with the Docker runner.

| Exit code | Meaning |
| --- | --- |
| 0 | Done, or stopped on request |
| 1 | Other failure |
| 2 | Bad command line, or a malformed enrolment code; for `link`: the link was denied |
| 3 | The control plane refused the code (wrong, used or expired); for `link`: the link expired unapproved |
| 4 | The issued certificate failed a check; nothing was saved; for `link`: the control plane couldn't be reached |
| 5 | Already enrolled; use `--force` |
| 6 | Config or identity problem (unreadable, inconsistent, or a key file others can read) |
| 7 | No GPU source: NVML missing, no NVIDIA GPU, or a build without NVML and no `--fake-gpu` |
| 8 | Not enrolled yet |
| 9 | `self-test`: a check failed |
| 10 | The control plane couldn't be reached, or its certificate isn't from our CA |

## The Windows app (ADRs 0033, 0034)

Five programs, built from this workspace, installed per user with no admin rights after setup:

| Program | What it does |
| --- | --- |
| `IdleO-Setup.exe` | The one download (about 7 MB; it carries no payload). Checks the laptop, turns WSL on (with resume after a restart), downloads and verifies the signed release, installs the app and the sealed Linux space, self-tests, links by approval and starts hosting. Two clicks of its own: **Set up**, and **Approve** in the browser. Run again, it repairs. |
| `idleo-launcher.exe` | Started at sign-in. Starts and watches the other two, restarts them with backoff, runs the 5-minute trial of a new version and rolls back if it isn't confirmed or crashes 3 times. `uninstall` removes everything. |
| `idleo-hostd.exe` | The agent's `run`, with no console window. |
| `idleo-tray.exe` | The state in colour and words, "Earned today", an update under way, Hosting on and off, Pause (an hour, until this evening, until tomorrow morning), Resume, Auto-pause's settings and Open dashboard. No Quit: turning hosting off is how a host stops. |
| `idleo-agent.exe` | The command line, as on any system. |

- **Folders:** programs in `%LOCALAPPDATA%\Programs\IdleO\` (`idleo-launcher.exe`, `current`, and an `app-<version>\` per installed version, two at most); data in `%LOCALAPPDATA%\IdleO\` (`agent\`, `updates\`, `setup\`, `wsl\<distro version>\`, `update-state.json`, `logs\`). Registry: `HKCU\…\Run\IdleO`, `HKCU\…\RunOnce\IdleO Setup` (setup's resume), `HKCU\…\Uninstall\IdleO`.
- **The local channel:** the tray and the launcher talk to `idleo-hostd` over `\\.\pipe\idleo-<owner SID>`, which only the owner and SYSTEM can open, in JSON lines that carry `"v": 1` (`idleo_release::channel`). No port is opened.
- **Auto-pause** (`agent/src/availability.rs`): keyboard or mouse input, a full-screen app or game, unplugged power, or the GPU busy with something not ours for 10 seconds pauses hosting within 2 seconds and stops a running session with a checkpoint first (`owner_returned`); 10 quiet minutes start it again. Settings in `settings.toml`, changed from the tray. Linux and macOS never pause.
- **Self-test** (`idleo-agent self-test [--quick]`, exit 9 on a failure): the sealed image registered and locked down, the GPU visible, the container runtime, then (not with `--quick`) the GPU inside a container (the image's `gpu-check`), the lockdown script and storage speed. Run after linking, after each update and when the tray asks; reported to the control plane. Skipped, and said so, on Linux, macOS and with fake GPUs.
- **Updates:** see ADR 0034. The release key is baked in at build time: the development key from `private/release-dev/release.pub` (`scripts/release/dev-key.sh`) or `IDLEO_RELEASE_DEV_PUBLIC_KEY` in a normal build, which says "Development build" and refuses control planes ending `idleo.in`; `release-keys/release.pub` with `--features prod-keys`, which fails while that file is empty. `IDLEO_APP_VERSION` at build time sets the version the programs report (`scripts/release/publish.sh` sets it).

**Building on the Mac:** `scripts/build-windows.sh` cross-builds the five programs for `x86_64-pc-windows-msvc` with cargo-xwin (`cargo install --locked cargo-xwin`) and Homebrew's `llvm` and `lld` (`llvm-rc` compiles the icons and manifests) into `dist/windows/`, and prints their sizes; `--release-keys` is a release build. `IDLEO_SETUP_API_URL`, `IDLEO_SETUP_APP_URL`, `IDLEO_SETUP_SITE_URL`, `IDLEO_SETUP_CONTROL_PLANE` (`host:port`) and `IDLEO_SETUP_CA_ROOT` set where the setup program points (the local stack without them; a release build needs every one). `scripts/checks/agent.sh` also lints the workspace for Windows with `cargo xwin clippy` when cargo-xwin is installed, and runs cargo-deny twice: `deny.toml` for every target (the tray and setup, whose Linux backends we never ship, left out of its graph) and `deny-windows.toml` for the Windows app alone. To try it on a laptop: `./start.sh --lan`, then `docs/windows-laptop-test.md`.

## Dependencies

Each is here for one reason. Versions are locked in `Cargo.lock`.

| Crate | Version | Why |
| --- | --- | --- |
| tokio | 1.53.2 | Async runtime: sockets, timers, signals; its paused test clock drives the backoff tests |
| tonic | 0.14.6 | gRPC client (and, in tests only, the server) |
| tonic-prost, prost | 0.14.6, 0.14.4 | Protobuf messages and their gRPC codec (`agent-proto`) |
| prost-types | 0.14.4 | Well-known types (`Duration`, `Timestamp`) in the generated code |
| tonic-prost-build | 0.14.6 | Generates the gRPC code at build time (build only) |
| protox | 0.9.1 | Compiles the .proto files in pure Rust, so no system `protoc` (build only) |
| rustls | 0.23.45 | TLS 1.3, with the `ring` provider, trusting only `ca_root`; TLS 1.2 isn't compiled into release builds |
| tokio-rustls | 0.26.6 | rustls on tokio streams, under our own connector |
| hyper-util, tower | 0.1.21, 0.5.3 | The glue that hands tonic our TLS stream as its connector |
| tokio-stream | 0.1.19 | Turns the outgoing message channel into the gRPC request stream |
| rcgen | 0.14.10 | Generates the P-256 key pair and the CSR |
| x509-parser | 0.18.1 | Reads the issued certificate's public key, host URN and expiry |
| pem | 4.0.0 | Writes `host.crt` |
| clap | 4.6.7 | The command line |
| serde, toml | 1.0.229, 1.1.6 | Reads `agent.toml` |
| serde_json | 1.0.151 | Writes and reads `host.json` |
| time | 0.3.55 | Certificate expiry times, formatted as RFC 3339 |
| uuid | 1.27.0 | UUIDv7 message ids, and host ids |
| getrandom | 0.4.3 | Backoff jitter and the fake GPUs' drift, from the system's random source |
| tracing, tracing-subscriber | 0.1.44, 0.3.23 | Structured logs, as text or JSON |
| nvml-wrapper | 0.13.0 | Reads NVIDIA GPUs through NVML, loaded at run time from the driver (feature `nvml`, Windows and Linux only) |
| windows | 0.62.2 | `GetLastInputInfo`, the build number, file access lists and `QueryPerformanceCounter` (`agent-windows`, Windows only) |
| rusty-s3 | 0.10.2 | Signs S3 requests (SigV4, with the grant's session token); does no I/O and never looks for credentials of its own, so an agent can't pick up ambient cloud keys |
| reqwest | 0.13.5 | HTTP for object storage, over our own rustls config |
| rustls-platform-verifier | 0.7.1 | Storage's public certificate is checked against the operating system's trust store, not a bundled one |
| rustix | 1.1.5 | `clock_gettime(CLOCK_BOOTTIME)` on Linux and `CLOCK_MONOTONIC` on macOS without unsafe code (Unix only) |
| tempfile | 3.27.0 | Temporary directories in tests, and the fakes' disks (`release`'s `fakes` feature) |
| ed25519-dalek | 3.0.0 | Checks the release manifest's Ed25519 signature; makes and uses release keys in `idleo-release` (`release`) |
| sha2 | 0.11.0 | SHA-256 of downloaded files, and the rollout's bucket (`release`) |
| base64 | 0.22.1 | The release key and signatures as text (`release`, `setup`) |
| zip | 9.0.5 | Unpacks the app zip, deflate only (`release`) |
| tray-icon | 0.26.1 | The tray icon and its menu (muda, re-exported) (`tray`, Windows only) |
| tao | 0.37.1 | The event loop the tray and the setup window run on (Windows only) |
| wry | 0.57.0 | The setup window: WebView2, part of Windows 10 22H2 and 11 (`setup`, Windows only) |
| embed-resource | 3.0.12 | Compiles the icon and the manifest into the Windows programs (`brand`, build only) |

**The rustls provider is `ring`**, not `aws-lc-rs`. ring builds with only a C compiler on all three systems (aws-lc-rs can need extra build tools, such as NASM on Windows), is smaller, and is the same library rcgen already uses, so the agent carries one crypto library instead of two. Its licence is plain Apache-2.0 and ISC. What we give up is `aws-lc-rs`'s post-quantum hybrid key exchange and its FIPS option; switching is a feature flag in two crates if we need them (ADR 0012).
