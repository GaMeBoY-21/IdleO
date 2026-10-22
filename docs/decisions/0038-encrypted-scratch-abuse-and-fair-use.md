# 0038. Encrypted scratch, abuse and fair use

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

ADR 0035 sealed a job in a container inside a Linux space the host cannot browse into by accident. Four gaps were left
(docs/prompts/sandbox-hardening.md):

1. The scratch disk was a plain ext4 image on the distribution's virtual disk. A host is the laptop's administrator, so
   after a session a curious host could copy the virtual disk and read a renter's files.
2. Nothing noticed a renter mining or cracking passwords on a host's GPU. Hosts, and our name, pay for that.
3. The ports were limited, but not the rate: a job could flood a home connection or use it to attack others.
4. The Terms were enforced only in the web app, so the command line and the SDK could book without anyone having agreed.

**The honest limit, kept in every document and screen that touches this:** a host with administrator rights can still read a
*running* job's memory. Encryption protects files at rest and after the session. It does not protect a renter from a
determined host during it, and nothing here claims to. The booking screen and the Terms keep saying: don't send secret
data to community hosts.

## Decision

### An encrypted scratch disk for each session

Each attempt's scratch disk is a LUKS2 volume (`aes-xts-plain64`, a 512-bit key) on a loop file, opened by `idleo-runner`
(`agent/runner/src/crypto.rs`). The key is 64 random bytes made for the attempt, passed to `cryptsetup` through a pipe,
zeroed after use, and never written anywhere, so it exists only in the runner's memory. Closing the volume (or the runner
restarting without it) makes what was on the disk unreadable, which is the "erase": the file is deleted afterwards as before.

- **Cheap to open.** The key is random, not a passphrase, so the key stretching is pointless: one PBKDF2 iteration count
  forced to 1000, a few milliseconds. Making and mounting a volume took 108 ms against 26 ms for a plain one on the
  development distribution, well inside the 2-second budget.
- **Everything the job writes is on it.** The job's `/work`, its outputs and checkpoints before upload, and, found while
  building this, the JSON file nerdctl keeps of every line a job prints (it sat on the distribution's own disk until the
  container was removed): that file is now a link onto the encrypted disk. Hostile test 15 writes a marker from the job
  and then searches the whole backing disk, free blocks included, for it after the session; a watch test does the same
  for every file the distribution gained, so a new leak fails the build.
- **No swap.** `MemorySwapMax=0` for the runner and `--memory-swap` equal to `--memory` for the container: a job's memory
  can't reach the virtual disk either. Hostile test 16 shows an unprivileged process in the distribution can't open a
  running session's mapping, scratch disk or image.
- **Refuses rather than runs plain.** If the kernel can't do dm-crypt, the runner says `sandbox_not_ready`, the self-test's
  `scratch_encryption` check fails with that sentence and the host isn't offered sessions. There is no "unencrypted"
  fallback.
- **The WSL kernel.** Microsoft's WSL kernel builds dm-crypt as a loadable module: `CONFIG_DM_CRYPT=m` at line 2406 of
  `arch/x86/configs/config-wsl` on `linux-msft-wsl-6.18.y` (commit d504d40, github.com/microsoft/WSL2-Linux-Kernel); the
  6.6 branch has `=m` at line 2461 and 6.1 has `=y`. The distribution therefore gains `kmod` and `cryptsetup-bin` (a
  loadable module needs `modprobe`); it stays under its 400 MB cap (a 150 MB tarball). This was read from the source, not
  yet run on a laptop: the first Windows run is the proof (docs/windows-laptop-test.md).

### Abuse: what stops a session and what only tells an admin

The runner looks for four things, reports each to the control plane, and only the strongest two stop anything.

| Signal | Does |
| --- | --- |
| A running process whose **executable's SHA-256** is on the blocklist | **Stops** the session (`abuse_stopped`) and suspends the renter. |
| A **second** look-up of a listed pool name in the same session | **Stops** it, the same way. |
| A running process whose **name** is on the blocklist | Flags: a review item, nothing else. |
| The **first** look-up of a listed pool name | Flags; the look-up is answered "no such name" (NXDOMAIN) so the pool isn't reached. |
| New connections dropped for 5 minutes running; or the minute-by-minute numbers looking like mining for 15 minutes (GPU busy, little GPU memory, next to nothing over the bus, a small steady trickle on the network) | Flags only. |

**Why heuristics only flag.** A fine-tune and a mining job both keep the GPU at 100%; PCIe traffic, memory use and
network trickle overlap with honest work on a bad day, and a false stop costs an honest renter their session and their
account. A name is trivially renamed, and anyone can recompile an open-source miner, so **a hash only catches an
unmodified build** and a name only catches the lazy; that is why the name flags, why the DNS check exists, and why none of
it is called protection. A determined renter can still reach a pool by IP address or through DNS-over-HTTPS (TCP 443
is open); what we do about that is look at the behaviour and the reports. These checks make mining a deliberate act that
is logged and costs the renter their account, which is the deterrent.

**The blocklist** is data, not code: `agent/runner/abuse/blocklist.json`, baked into the runner, so a new list is a new
distribution (docs/runbooks/abuse.md, "Changing the blocklist"). Every entry names its source. It holds 13 program names
(miners and password crackers), 18 pool domains, each taken from the pool's or project's own site, and **no hashes**.
Hashes need the executables of each release, from each project's official release page, hashed and then deleted, never
run, never committed. I did not do that in this round: fetching and handling live miner and cracker binaries is a step to be
taken by a person who has decided to, in an environment prepared for it, and the list is useful (names flag, pools stop)
without it. Until someone fills `hashes`, **nothing stops on a hash alone**, and the report says so. The tests use test
entries that only a development distribution's marker file (`/etc/idleo/development`) adds; a release distribution has none.

**The DNS forwarder.** A job's DNS goes to its bridge gateway, where the runner answers for that session only: it refuses
listed pools and forwards everything else to the four public resolvers as before. It logs only a blocked name, in the
session's own record, never what else was asked. The nftables rules open UDP and TCP port 53 to the session's own gateway
address and nothing else on it (hostile test 4 still shows every other gateway port, and the distribution's own addresses,
unreachable).

**Who sees it, and what happens.** An item in the admin console's Abuse screen holds the signals, the last 60 minutes of
the job's numbers, and the session's cost. Only a **stop** suspends the renter, at once, with the actor `abuse-watch` in the
audit log. The admin then:

- **confirms** it (the suspension stays), or **confirms and bans**: a ban is refused exactly like a suspension and the person
  is told the same plain sentence; only **clearing** the item lifts it, with a typed reason in the audit log. A held account
  can still read its data, export it, delete the account and write to support (the privacy rights don't depend on good
  standing);
- **clears** it: the account is reinstated and the session's charge is **refunded in one ledger transaction** (the
  commission first, then the hosts' shares in proportion, as for a dispute; `txn_id` a name-based UUID of the review);
- adds a **note**, which is its own reason in the audit log.

An alert (`AbuseStops`) fires on more than three stops in an hour, with a runbook section: that many is a campaign or a
bad blocklist entry, not coincidence.

### Fair use of a host's internet

nftables, not `tc`: the distribution has no `tc`, and WSL's 6.1 kernel has neither TBF nor HTB, while `limit` is on every
kernel. So both limits drop packets over the rate rather than queue them, in one table (`inet idleo_fair`) the runner owns,
per session.

- **New connections:** 50 a second with a burst of 100. A flood of 5,000 attempts completed 320.
- **Upload:** half of what the agent measured on the laptop's own connection, never under 5 Mbit/s (a slow line still lets
  a job upload a checkpoint). The agent measures it and tells the runner in `upload_cap_kbps` (an additive protocol field).
  A dropped packet makes TCP back off, so a normal upload settles close to the cap: measured at 99 to 104% of it over a
  20-second flood (110 to 118% for shorter runs, where the burst allowance shows).
- **The runner's own uploads are exempt**, so a job flooding its line never slows its own outputs or checkpoints leaving
  (100 MB took 1.7 s alone and 1.5 s beside a flooding job).
- The owner sees the upload cap on the machine page ("Jobs use at most 25 Mbit/s of your upload, so your own internet
  stays usable"), because it is their connection.

### Terms on the API

Creating calls (`POST` of sessions, uploads, hosts, host links and the like, marked `@RequiresAcceptedTerms`) answer 403
`terms-not-accepted` until the person has accepted the Terms and privacy notice now in force; its detail says where
(the web app's `/agree/` page). Reading, stopping, pausing, removing and downloading stay open, so a person can always see and
leave what they have. An API key can't accept for the person: acceptance is `@BrowserSignInOnly`. The CLI and the SDK show
the sentence and link (exit code 5, "not signed in", in the CLI, which a browser acceptance fixes; `TermsNotAccepted` in the SDK).

### Small things

- **Operators:** who is behind each `OPERATOR` handle is written in `private/operators.md` (git-ignored; the template and where
  it lives are in docs/runbooks/admin.md). Real names never go in the repository.
- **Ticket answers stay as they are.** The admin's answer form is unchanged; the answer text itself is the audited reason
  (ADR 0037), so there is no second box to fill.

## What is left on purpose

- **Behaviour numbers are kept 90 days** (`legal.retention.behaviour`) and **abuse reviews 365 days**
  (`legal.retention.abuse-reviews`), both `LEGAL(confirm)`: a lawyer sets them.
- **What the Terms disallow** is the lawyer's text. The booking screen only links to the Terms.
- **A host can read a running job's memory**, and a renter can use a pool by IP address or DNS-over-HTTPS. Both are said above
  and in the runbook rather than hidden.
- **dm-crypt under the real WSL kernel, the bandwidth cap on a home connection and the GPU's PCIe numbers on a laptop GPU** can
  be proved only on a laptop (docs/windows-laptop-test.md).

## Consequences

- A leaked virtual disk shows a renter's files as noise; a stolen laptop shows nothing of a past session.
- An honest renter is never stopped by a guess: only a listed build or a second pool look-up stops a session.
- A renter who doesn't know their own tool uses a pool name in a config will be stopped on the second look-up, suspended,
  and refunded when an admin clears them. That is the cost of a stop that works.
- Opening a volume adds tens of milliseconds to a start. The cipher's own cost on a laptop is unmeasured here (the
  development numbers are dominated by the Mac host's disk cache), which is why the report lists it as laptop-only.
- The runner and agent protocol grew by additive fields and events only (`upload_cap_kbps`, `abuse`, `behavior`).
