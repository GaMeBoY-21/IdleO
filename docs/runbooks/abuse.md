# Abuse: mining, cracking and the review queue

What to do when a host's runner flags or stops a session for software no renter may run on a host's GPU (ADR 0038). The
`AbuseStops` alert (docs/runbooks/alerts.md) points here; so does an admin opening **Abuse** in the console.

**What the runner can and can't do.** It looks at four things. Two **stop** the session and suspend the renter at once: a
running program whose executable's SHA-256 is on the blocklist, and a second look-up of a listed mining pool in the same
session. Two **flag** only, for you to look at: a program whose *name* is listed, and the first look-up of a listed pool
(answered "no such name", so the pool isn't reached). Two more flag and stop nothing: new connections dropped for 5 minutes
running, and the job's numbers looking like mining for 15. A hash only catches an unmodified build (anyone can recompile an
open-source miner), a name only catches the careless, and a determined renter can still use a pool by IP address or
DNS-over-HTTPS. So an empty queue doesn't mean nobody is mining; the numbers and the reports are the rest of the net.

## An item in the queue

Admin console → **Abuse**. Open items are first, oldest first. Open one to see:

- **What was seen**: each signal with its time, the name or domain (and the hash, for a listed build), and whether the
  runner stopped the session on it.
- **What the job did each minute**, the last 60: GPU use, share of GPU memory, traffic over the bus, bytes sent and received,
  lines printed, connections dropped. A miner works the GPU hard (near 100%) with little of its memory, next to nothing over
  the bus and a small steady trickle over the network. Training and rendering don't look like that. These numbers never
  stop anyone; they only tell you where to look.
- **What the renter paid** for the session, and the notes so far.

Then decide, and write a reason (5 to 500 characters; no email or address in it):

| Choice | When | What it does |
| --- | --- | --- |
| **Confirm** | It is what it looks like | The item is confirmed; the account stays suspended (if the runner stopped it). Nothing else changes. |
| **Confirm and ban** | Mining or cracking, clearly, or the same person again | The account is banned. A ban is refused exactly like a suspension and the person sees the same sentence, so it tells them nothing to work around. It is lifted **only** by clearing this item, with a typed reason. The person can still see and export their data, delete the account and write to support. |
| **Clear and refund** | It was something else (a notebook that renders, a tool that looks up a pool name for a reason you've checked) | The account is reinstated, a ban included. The session's charge is refunded in one ledger transaction (the commission first, then the hosts' shares in proportion), the same as a refunded report. |
| **Add a note** | You've asked the renter, or looked at the host | Kept on the item, and its own reason in the audit log. |

Everything is in the audit log by id (`abuse-watch` for what the runner did by itself, your user id for what you did).

### What to say

To a **renter** who wrote in because their account is suspended (they see "Your account is suspended" and are told to write
to us; they aren't told why until you answer):

> We stopped your session because the job it ran matched software we don't allow on other people's GPUs (the Terms list
> what that covers). If that's a mistake (for example, a tool that looked up a mining pool's name for a reason of its own),
> tell us what the job was and we'll look again. If it's cleared, your account is back and the session is refunded.

If you confirmed it: say that it was confirmed and the account is suspended or closed, quote the Terms, and don't describe
what the runner looks for beyond that. If you cleared it: say it's cleared, the credit is back, and that the stop was ours.

To a **host** who asks what happened on their machine (they never see the renter or the job):

> A job on your laptop did something our rules don't allow, and we stopped it and dealt with the account. Nothing on your
> laptop was touched: jobs run in a sealed space with no view of your files or your home network. You were paid for the
> time the job ran.

Only say the last line if the money records show it: a cleared item refunds the renter from the commission first and then
the hosts' shares, so check the earnings first.

## When the alert fires

`AbuseStops` is more than 3 stops in an hour across the fleet. That many is a campaign or a bad entry in the blocklist, not
coincidence (docs/runbooks/alerts.md has the first checks). A **bad entry** (a pool domain that is also used for something
honest, say): clear every item it stopped (that reinstates each account and refunds each session), then take the entry out
(below). Until a laptop has the new list it will go on stopping honest renters.

## Changing the blocklist

The list is data: `agent/runner/abuse/blocklist.json`, with its `version`. Each entry names its **source**: a program or a
pool's own site or release page. It is read into the runner when it is built, so a new list is a **new distribution**
(`agent/distro/VERSION`), delivered like any release (the manifest's `distro`, ADR 0034). A laptop uses the list of the
distribution it runs.

1. **Names and pool domains.** Edit the file: add `{ "name": "…", "kind": "miner" | "password_cracker", "source": "https://…" }` or
   `{ "domain": "…", "source": "https://…" }`. A name is lower-case, at most 15 characters (what Linux keeps of a program's name), and matched against a running program's name; a domain matches itself and
   every name below it. Put in only what you can cite.
2. **Hashes.** A listed build stops a session, so it must be exact and sourced. For each project: download only from its
   **official release page**, into a fresh empty folder; hash the executables (`shasum -a 256`); **delete the archives**;
   never run them, never commit them; and put the release URL in `source` beside the hash:
   `{ "sha256": "…", "name": "xmrig 6.22.0 windows", "kind": "miner", "source": "https://github.com/xmrig/xmrig/releases/tag/v6.22.0" }`
   (the `source` must be an https address; a hash is 64 lower-case hex digits).
   Do it on a machine that isn't a laptop that hosts. As of 2026-10-22 the list has **no hashes**: nothing stops on a hash
   alone until someone fills them in.
3. Bump `version`, then run `cargo test -p runner abuse` from `agent/` (the file must parse, every entry needs a source, and a
   release distribution takes no test entries).
4. Build and ship a distribution: `scripts/build-distro.sh`, then a release (`scripts/release/publish.sh`), with a small
   rollout first. Watch **Abuse** and the `AbuseStops` alert for honest renters being stopped; if one is, clear it and pull the entry.

## Testing it yourself

A development distribution (`scripts/dev-distro.sh`) adds test entries from `/etc/idleo/development`, so a harmless
program can stand in for a miner: `agent/runner/tests/hostile/run.sh` and `scripts/e2e/real-runner.sh` use them to stop a
session, open an item, and clear it. A release distribution has none, so a test name can never stop a renter's job.
