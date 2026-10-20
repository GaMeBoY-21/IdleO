# The job runner

What to do when jobs don't run, or run wrong, on a host (ADR 0035). The renter sees one plain sentence for each ending
(`api/endings.json`); this page is for the person who has to find out why.

## Where to look

| What | Where |
| --- | --- |
| The host's self-test, with the runner's checks (`runner`, `gpu_in_container`, `cuda_matmul`, `network_lockdown`) | Admin console, Hosts, the host; or `scripts/install-report.sh` for a laptop on the local stack |
| The ending a session had | The session's page in the admin console; its code is one of `api/endings.json` |
| The runner's own log, one line for each step of an attempt, with how long each took (`timings`) | In the distribution: `journalctl -u idleo-runner` (a laptop: `wsl -d IdleO-<version> -u root -- journalctl -u idleo-runner`; the local stack: `scripts/dev-distro.sh exec -- journalctl -u idleo-runner`) |
| The sandbox's rules as loaded | `idleo-runner rules` inside the distribution; its table comment carries the hash of `agent/distro/sandbox.nft` |
| What's running | `nerdctl --namespace idleo ps` inside the distribution |

## By ending

| Code | Meaning | What to do |
| --- | --- | --- |
| `image_pull_failed` | The job image couldn't be downloaded, or isn't pinned by digest | Is the registry reachable from the laptop? Does the catalog's digest exist there (`scripts/images/publish.sh`)? A digest that isn't in the registry is never committed. |
| `sandbox_not_ready` | The nftables rules aren't loaded, so no job runs | `systemctl status idleo-sandbox` inside the distribution. The runner refuses to start a job without them, on purpose; never work around it. |
| `invalid_input` | An input or a resume checkpoint couldn't be staged or used | The renter's, and the job's own words say how. If every input of every renter fails, look at storage. |
| `out_of_memory`, `scratch_full`, `timed_out` | A limit the template set was reached | Not a fault; the renter chooses more memory, less disk use or more time. If a template's limits are wrong, change the catalog. |
| `outputs_upload_failed` | The job finished but its outputs couldn't be saved | Storage or the laptop's connection. The renter is not charged for it as a fault of theirs. |
| `runner_failed`, `bad_request`, `unsupported_version` | The runner or the agent broke the protocol | A bug or a half-finished update. The agent and the runner are compatible within a protocol version; `hello` says each one's. Update the laptop. |
| `lease_expired` | The agent stopped renewing: the runner stopped the job on its own clock | The laptop went offline or asleep. Not a fault; the session resumes from its last checkpoint elsewhere. |

## A job that reaches somewhere it shouldn't

Treat it as a security incident. The 14 hostile tests (`agent/runner/tests/hostile/run.sh`) and the self-test's
`network_lockdown` exist to say it can't. If a host's self-test reports `network_lockdown` failed ("a job can reach the
home network"), suspend the host (`suspend-host`, docs/runbooks/hosts.md) and keep its report: it names the addresses that
answered.
