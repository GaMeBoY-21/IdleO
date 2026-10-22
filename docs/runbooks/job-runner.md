# The job runner

What to do when jobs don't run, or run wrong, on a host (ADR 0035). The renter sees one plain sentence for each ending
(`api/endings.json`); this page is for the person who has to find out why.

## Where to look

| What | Where |
| --- | --- |
| The host's self-test, with the runner's checks (`runner`, `scratch_encryption`, `gpu_in_container`, `cuda_matmul`, `network_lockdown`) | Admin console, Hosts, the host; or `scripts/install-report.sh` for a laptop on the local stack |
| The ending a session had | The session's page in the admin console; its code is one of `api/endings.json` |
| The runner's own log, one line for each step of an attempt, with how long each took (`timings`) | In the distribution: `journalctl -u idleo-runner` (a laptop: `wsl -d IdleO-<version> -u root -- journalctl -u idleo-runner`; the local stack: `scripts/dev-distro.sh exec -- journalctl -u idleo-runner`) |
| The sandbox's rules as loaded | `idleo-runner rules` inside the distribution; its table comment carries the hash of `agent/distro/sandbox.nft` |
| What's running | `nerdctl --namespace idleo ps` inside the distribution |

## By ending

| Code | Meaning | What to do |
| --- | --- | --- |
| `image_pull_failed` | The job image couldn't be downloaded, or isn't pinned by digest | Is the registry reachable from the laptop? Does the catalog's digest exist there (`scripts/images/publish.sh`)? A digest that isn't in the registry is never committed. |
| `sandbox_not_ready` | The nftables rules aren't loaded, or this machine's kernel can't encrypt a scratch disk, so no job runs | `systemctl status idleo-sandbox` inside the distribution. For encryption see "Scratch encryption" below. The runner refuses to start a job without either, on purpose; never work around it. |
| `abuse_stopped` | The runner stopped the job for running a listed build or looking up a mining pool a second time | An admin's decision, not a fault: docs/runbooks/abuse.md. |
| `invalid_input` | An input or a resume checkpoint couldn't be staged or used | The renter's, and the job's own words say how. If every input of every renter fails, look at storage. |
| `out_of_memory`, `scratch_full`, `timed_out` | A limit the template set was reached | Not a fault; the renter chooses more memory, less disk use or more time. If a template's limits are wrong, change the catalog. |
| `outputs_upload_failed` | The job finished but its outputs couldn't be saved | Storage or the laptop's connection. The renter is not charged for it as a fault of theirs. |
| `runner_failed`, `bad_request`, `unsupported_version` | The runner or the agent broke the protocol | A bug or a half-finished update. The agent and the runner are compatible within a protocol version; `hello` says each one's. Update the laptop. |
| `lease_expired` | The agent stopped renewing: the runner stopped the job on its own clock | The laptop went offline or asleep. Not a fault; the session resumes from its last checkpoint elsewhere. |

## A job that reaches somewhere it shouldn't

Treat it as a security incident. The 16 hostile tests (`agent/runner/tests/hostile/run.sh`) and the self-test's
`network_lockdown` exist to say it can't. If a host's self-test reports `network_lockdown` failed ("a job can reach the
home network"), suspend the host (`suspend-host`, docs/runbooks/hosts.md) and keep its report: it names the addresses that
answered.

## Scratch encryption

Every session's scratch disk is a LUKS2 volume whose key is made for the attempt and lives only in the runner's memory
(ADR 0038). When the session ends the volume is closed and erased; if the runner restarts it adopts running jobs, and a
volume whose key it no longer has is simply unreadable, which is the point.

- **The self-test's `scratch_encryption` failed on a laptop** ("the kernel can't make an encrypted volume"): the runner will
  say `sandbox_not_ready` and the host isn't offered sessions. Check inside the distribution that the kernel has dm-crypt:
  `modprobe dm_crypt` and `grep dm_crypt /proc/modules`. Microsoft's WSL kernel ships it as a module (`CONFIG_DM_CRYPT=m`);
  an old WSL kernel or one the owner replaced with `kernel=` in `.wslconfig` may not. Ask the owner to run `wsl --update`
  and remove a custom `kernel=` line, then run the self-test again. Never make a plain disk to get past it.
- **A development distribution that can't** (a Mac whose Docker VM has no dm-crypt) only says "skipped" and makes plain disks.
- **Nothing of a session should be outside the encrypted disk**, the job's printed lines included (nerdctl's own log file is a
  link onto it). If you find a file of a session's elsewhere in the distribution, that is a leak: keep it, suspend the
  host, and hostile test 15 or the watch test in `agent/runner/tests/hostile/` needs a new case.
- **A host with administrator rights can still read a running job's memory.** Say so if asked; don't promise otherwise.

## Fair use of the host's internet

Each session gets two limits, in one table (`inet idleo_fair`) the runner owns (ADR 0038): 50 new connections a second (burst
100) and an upload of at most half of what the agent measured on the laptop's line, never under 5 Mbit/s. They drop packets
over the rate rather than queue them. The runner's own transfers (outputs, checkpoints) are not counted. The owner sees the
upload figure on the machine page. A renter's complaint that "uploads are slow" is this: the cap is real. Before the host
app has checked the line's speed the floor applies.

## Abuse

A listed build or a second mining-pool look-up stops a job (`abuse_stopped`) and suspends its renter; a listed name, a first
look-up, dropped connections or mining-like numbers only flag. All of it, and what to say, is docs/runbooks/abuse.md.
