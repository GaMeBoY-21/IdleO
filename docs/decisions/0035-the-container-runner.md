# 0035. The container runner

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Until now the agent ran sessions with a development runner that only simulated them (ADR 0016), so nothing proved that a
renter's code can run on a stranger's laptop without reaching the owner's files or the owner's home network
(architecture rule 6). Phase 1 needs the real thing: jobs in
containers inside the sealed Linux distribution (ADR 0033, `agent/distro/`), a job image that holds PyTorch and CUDA, and
a contract for a job's inputs, outputs and checkpoints (ADR 0022: renters bring their own model, data and code). It has to
be developed and tested on a Mac, where WSL2, an NVIDIA GPU and the Windows firewall are all missing, and it must never
run a job without its limits.

## Decision

**A runner of our own inside the distribution.** `idleo-runner` (`agent/runner/`) is a systemd service of the distro that
listens on a root-only Unix socket. The agent never sees a container, a file path inside the distribution or a Linux
credential: it reaches the runner through `idleo-runner attach`, a process it starts with `wsl.exe -d IdleO-* -u root --`
on Windows or `docker exec -i <name>` in development, and talks JSON lines to it (`{"v":1,…}`, requests with ids, replies and
events; `agent/jobcore/src/protocol.rs`, the one definition both sides compile). Requests: `hello`, `list`, `start`,
`stop` (with a grace), `lease`, `refresh` (fresh storage grants), `pull`, `cancel_pulls`, `forget`, `self_check`. Events:
`state`, `logs`, `checkpoint`, `exited`, `pull`. If the link dies the runner keeps its jobs; a restarted agent adopts the
sessions that survive (`list`), so an agent update or crash costs a job nothing while its lease is alive.

**The runner owns the attempt**, and the agent only decides and reports. Its steps are: the sandbox's rules are loaded;
the image is pulled by digest; a scratch disk is made; inputs and the resume checkpoint are staged; the container runs
while the runner pumps its logs, sweeps its checkpoints and watches the lease, the stop request and the maximum runtime;
outputs are uploaded; logs are flushed; everything is removed. An `exited` event is sent only after the logs are
uploaded. A lapsed lease stops the job on the runner's own clock (`lease_expired`), so a dead agent does not leave a job
running. Storage grants live only in the runner's memory (their `Debug` is redacted) and are replaced by `refresh`.
Every failure is a stable code: `image_pull_failed`, `invalid_input`, `sandbox_not_ready`, `out_of_memory`,
`scratch_full`, `timed_out`, `job_failed`, `lease_expired`, `outputs_upload_failed`, `runner_failed`, `bad_request`,
`unsupported_version`, beside the existing `input_unavailable` and `resume_checkpoint_missing`; each has words in
`api/endings.json`.

**The sandbox**, all of it set for every container and none of it a choice of the job: user 10001, read-only root, a
1 GiB tmpfs at `/tmp`, a loop-mounted ext4 file as `/work` of exactly the template's scratch size, inputs read-only, all
capabilities dropped, `no-new-privileges`, the default seccomp profile, memory, CPU and process limits from the template, the GPU
only when the job asks for one. Each session gets its own bridge (`idl<N>`, `172.30.<N>.0/24`); one nftables table
(`agent/distro/sandbox.nft`, hash-tagged, loaded by `idleo-sandbox.service` and checked by the runner before every start,
refused if it differs) allows a job exactly DNS to four named public resolvers and TCP 80 and 443 to public addresses.
Everything private, link-local, carrier-grade NAT, loopback, multicast and reserved, the default gateway (Windows itself
in WSL's NAT mode) and the distribution's own addresses are dropped by destination, so the rules hold in either WSL
networking mode. No IPv6. Nothing starts a connection to a job. Fourteen hostile tests
(`agent/runner/tests/hostile/`) run as a job would and must all pass.

**Plain containerd with nerdctl and CNI**, not Docker: no daemon with a socket a job could be handed, a smaller image
(the distribution stays under 400 MB), and every flag explicit in `agent/runner/src/nerdctl.rs`. The distribution has no
Docker, sshd, compiler or sudo.

**Images** are pinned by digest in the signed catalog (`templates/catalog.yaml`), so the digest is the proof: a pull that
returns other bytes fails. We do not run cosign on our own images, since the catalog already pins the exact bytes and
anything unlisted never runs; cosign returns with custom images for verified renters. The agent pre-downloads the images
the control plane says are worth having (`Welcome.prefetch_images`, an additive proto field; `ImagesToPrefetch`, declared
by agentgateway and implemented by sessions) when the laptop is idle and the connection isn't metered, and stops the
distribution after 5 idle minutes. One job image (`agent/images/pytorch/`, CUDA 12.8.1, PyTorch 2.11.0, the newest on the
cu128 index; a driver of at least 527.41, `MIN_NVIDIA_DRIVER`) serves both services. **Fine-tune** (QLoRA, the renter's
model and examples, a LoRA adapter out) and **Run your code** (the renter's folder, their command) read the same job
contract: `SESSION_PARAM_<NAME>`, `SESSION_INPUT_<NAME>_DIR`, `SESSION_OUTPUT_DIR`, `SESSION_CHECKPOINT_DIR` (a checkpoint
is `/work/checkpoints/<n>/` with a `_DONE` file inside), `SESSION_RESUME_DIR`, `SESSION_CHECKPOINT_INTERVAL`, a SIGTERM
then a grace on stop, and `SESSION_FAILURE_FILE` (`/work/idleo-failure.json`), of which the runner believes only
`invalid_input`.

**Development without a GPU or Windows.** A development distribution (`scripts/build-distro.sh --dev`,
`scripts/dev-distro.sh`) is the same image plus a marker file `/etc/idleo/development`, which lets a job run without a GPU
and maps the logical registry name `registry.idleo.test:5000` to a plain-HTTP registry (`registry:2` in compose.yaml);
release distributions have neither, and the setup's measurements say which kind it was. `./start.sh --real-runner` runs the
stack on it, with its own catalog (`templates/development-real.yaml`, whose real templates differ from production's only
in lines marked `# dev:`, CPU-only arguments and a 20 s checkpoint interval). `scripts/images/publish.sh` publishes the
development image to that registry and, with `--release`, the CUDA image to GHCR and its digest into the catalog.

## Alternatives considered

- **Docker in the distribution**: the daemon's socket is a root-equivalent handle, its networking edits iptables behind
  our rules and the image is larger. containerd with nerdctl does everything we use.
- **The agent talking to containerd directly from Windows**: it would put Linux paths, mounts and credentials into the
  Windows program. A runner behind one narrow protocol keeps those in the one place that already is a Linux machine, and
  the same code runs unchanged in a Mac's development distribution.
- **iptables rules by source network (Docker's DOCKER-USER)**: they depend on Docker's chains and on addresses that
  change with WSL's networking mode. nftables rules by destination, checked before every start, hold in both.
- **An x86-64 development distribution on the Mac under Rosetta**: containers cannot nest there (no `mount_setattr`
  or seccomp in the emulated kernel), so the development distribution is arm64 native with a CPU build of the image. What
  that cannot show is listed in the report and in docs/windows-laptop-test.md: the real GPU, WSL's two networking modes
  and the Windows firewall are proven only on a laptop.
- **One catalog for production and development**: a test requires the development catalog to contain the production
  services word for word, with one simulated template each; the real templates need a registry only a developer has, so
  they live in their own file.
- **Cosign for our images**: see above; it adds a signing step and a verifier without adding a proof the digest doesn't
  already give.

## Consequences

- A job can no longer touch the owner's files or home network, and the claim is tested: fourteen hostile cases (sixteen since ADR 0038), the
  self-test's `network_lockdown` on every laptop, and the real-runner end-to-end test (`scripts/e2e/real-runner.sh`).
- The runner and the agent are separate programs with a protocol to keep compatible: it changes additively
  (`unsupported_version` otherwise), and `hello` reports the runner's version and the images it has.
- A distribution update replaces the runner, so a new runner version waits for an idle moment like any update (ADR 0034).
- Pulling a 6 GB CUDA image is the longest step of a first job; pre-download hides it when the owner's connection allows.
  Pull progress shows "N GB downloaded", because a registry doesn't say the total up front.
- The image's contents are ours to keep patched: the lock files pin every package, and `agent/images/pytorch/lock.sh`
  regenerates them.
- Open question: the real GPU's behaviour under Windows 10's WSL2 and under mirrored networking is unmeasured until the
  borrowed laptop arrives (Phase 0); the numbers in the report are from the Mac's stand-in.
- Open question: a job that needs more than HTTPS (for instance git over SSH) is refused; wider egress on request, as
  the plan allows, is not built.

## Addendum (2026-10-20): checks before the laptop day

- **The rules are checked by content, not by a comment.** Before every start the runner reads both tables back with `nft -j list
  table` and compares them with what its own copy of the rules file makes: the file is loaded in a network namespace of its own
  (`unshare --net`), where it changes nothing, and read back the same way, once per runner process, so the two are in the same form
  whatever `nft` does to what it's given. nft's version, the kernel's handles and the one set that depends on the network (the
  gateway and the VM's own addresses, refreshed before every start) are left out; every other set, chain, hook and rule counts, in
  order. A difference loads the rules again, once (one `nft` transaction, so there is no moment without them), and checks again;
  only a match starts the job, else `sandbox_not_ready`. A rule changed by hand under the same comment is found: the test is to change
  one in the development distribution.
- **More ranges are blocked**: 192.0.0.0/24 (IETF protocol assignments), 198.18.0.0/15 (benchmarking, which some routers, hotspots
  and VPNs use) and the documentation ranges 192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24, in the rules and in what the hostile
  test and the self-test try.
- **The hostile tests run through the real path too** (`run.sh --host`): a hidden, development-only template, `hostile-test`, in
  `templates/development-real.yaml` (not `development.yaml`, whose jobs the development runner only simulates), runs the hostile image
  with one attack named by a parameter; the driver books it as the host's owner, with the owner's other hosts paused so the scheduler
  can only choose the chosen one, and reads each session's end and the attack's own verdict line. What that path can't see is N/A
  with the reason, never PASS: row 11 (two sessions at once, and the other's address inside the VM) and row 12 (the VM's own processes
  and scratch disks after the session). Rows 4 and 9 say what they could not check from there (the gateway and the VM's own addresses:
  the self-test's `network_lockdown` probes them; the host's free disk: Windows' disk image grows). The same 14 run on amd64 in a
  GitHub workflow by hand and weekly (`.github/workflows/hostile.yml`), where the distribution is built for linux/amd64 natively.
- **A kill for memory is told apart without a fixed wait.** The container's cgroup is gone the instant its process exits (measured:
  `memory.events` absent at the first look), so its `oom_kill` counter can't be read after the exit as first hoped. containerd's event
  stream carries both the OOM event and the container's exit event, in order: the runner reads the exit from the same stream, and
  once it has, every OOM event before it has been read. The counter read every 10 ms while the job runs stays as a second source, and
  a stream that isn't delivering costs up to 500 ms before the decision is made without it.

## Addendum (2026-10-20): what a finished session leaves is checked, and row 12 reads it through the real path

- **`leftovers`, a check of the runner's `self_check`** (also in its quick form, which the agent runs at every connection, and in the
  agent's `runner` self-test check, which fails with its words): no container, no process in a container's cgroup, no mount, loop
  device, scratch file or folder, no session network interface and no address lease that no running session holds. It reads the
  distribution itself and tells what a running session holds (its container, scratch disk and network slot, tracked by the runner)
  from what is left. A session's bridge is not one: the 32 bridges are prepared once and reused, like the 32 CNI configs, an empty one
  is not a session's, and the image has no `ip` to take it down (and shouldn't); what a session attaches to it, a container's virtual
  interface and host-local IPAM's lease file, is checked.
- **A development distribution says it in each session's log**: before a session's job starts the runner writes one line, "idleo-runner
  (development): nothing is left from finished sessions", or "...: left from finished sessions: 1 process, 2 mounts". There is no
  channel from the Mac to a laptop's runner except sessions (the owner's API can't ask the agent for a self-test), so the real-path
  hostile test (`run.sh --host`) reads it where it can: the attack for row 12 starts a background process and ends, a following session
  runs, and that session's log says what the runner found. Release distributions say nothing: the marker file
  (`/etc/idleo/development`) is what turns it on. A laptop whose runner isn't a development one fails row 12 ("this can't be proved from
  here"), never passes it.
