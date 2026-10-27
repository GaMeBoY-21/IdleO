# 0041. Host quality

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Matching ranks batch hosts by price, reliability, bandwidth and cache, but until now three of those
were placeholders: every host counted as 0.5 for reliability and bandwidth, and nothing counted as cached
(`Scoring.java`, ADR 0015). The data now exists: sessions and why they ended (ADR 0016), self-tests (ADR 0034), the
pre-download inventory (ADR 0035), renters' reports and the admin's flag on a host (ADR 0028, ADR 0039).

An owner also had no way to ask their machine to check itself, short of the tray; and the abuse blocklist (ADR 0038)
was compiled into the runner, so a wrong entry could only leave with a new distribution.

## Decision

### The host score (`hosts` module)

- **Owned by hosts**, in `hosts.host_scores`, worked out for every host nightly (03:30 India time,
  `hosts.score.refresh-cron`) and for one host when sessions says one of its sessions ended or lost an attempt
  (`HostScoreRefresh` on `SessionEnded` and `AttemptLost`) or when its self-test is reported. Scheduling reads it through
  `SchedulableHosts.Candidate` (`reliability`, `bandwidth`), the one query behind matching, quotes and availability.
  A host with no score counts as 0.5 on both, as before.
- **Reliability (0 to 1)** is `0.8 × session score + 0.2 × self-test score − penalty`, over the last 30 days, every
  observation counting half as much for each 10 days it is old:
  - the session score is a Bayesian average with a prior of 5 sessions at 0.5: sessions the host finished against
    attempts it lost. A host **finished** a session when the attempt it ran is the one that completed it, whoever
    stopped it. It **lost** an attempt when its lease lapsed, it went missing after a reconnect, or its agent fenced or
    stopped the attempt. A renter's own stop, a job that fails, a renter cancelling, and an owner returning to the
    machine (`owner_returned`: a checkpoint, then the session carried on elsewhere) never count against it. Sessions
    implements hosts' `HostSessionResults` port from its own tables (`sessions.attempts.lost_reason`), so hosts never
    reads them.
  - the self-test score is the same average with a prior of 2, over every self-test the agent reported
    (`hosts.self_test_results`; `hosts.self_tests` keeps only the latest).
  - the penalty is 0.10 for each upheld report (a report an admin answered with a refund) and 0.15 for each admin flag
    (`trust.host_flags`), each fading like the rest, at most 0.5 together. Trust implements hosts' `HostTrustSignals`
    port.
  - A host with no history is exactly 0.5. One finished session moves it to about 0.56; one lost session to about 0.43,
    so a new host is not buried by its first bad session.
- **Bandwidth (0 to 1)** is the mean of the self-test's last upload and download speed against the 90th percentile of
  the same figure across hosts that are not removed, capped at 1; 0.5 until measured. The speed check now reports the
  download too (`SelfTestReport.download_kbps`).
- **Cache (0 or 1)** is 1 when the host's last heartbeat lists the template's image digest in the new additive
  `Heartbeat.cached_images`. The agent lists the digests of the images its pre-download remembers
  (`care.rs`); the control plane keeps the list in the host's presence record, drops entries that are not digests and
  keeps at most 64.
- **Shown:** the owner sees "Reliability: Good / Fair / New" on the machine page (Good from 0.70; New while fewer than
  5 sessions in the window and not below 0.45) with what lowers it: sessions lost, failed self-tests, and "renters'
  reports" without a count and without saying that an admin flagged the host (the flag stays the admin's, ADR 0039).
  Admins see every number on the host's page. Renters see nothing new.
- Ranking stays deterministic: equal scores go to the lower host id.

### Run self-test

- A button on the machine page and on the admin's host page asks the agent, over the existing stream, to run its full
  self-test (`RunSelfTest`, acked). The agent sends one `SelfTestProgress` per check as it ends and then the existing
  `SelfTestReport` carrying the run's id, or one `SelfTestProgress` with `refused` (`session_running`,
  `already_running`). The page reads the run every 2 seconds and shows every check from the start, waiting, so the list
  does not grow under the person's eyes.
- Rules, in `SelfTestRuns` for both pages: one run at a time (a run unanswered after 10 minutes is lost and stops
  counting); not while the machine is offline (`host-offline`) or runs a session (`host-busy`); at most 6 a host in 24
  hours (`self-test-limit`, refused runs included). The button says why in the server's own words. An admin pressing it
  gives a reason and is audited (`host.run_self_test`); the owner's own press is not audited. Like pausing, it is open
  to an API key.

## Alternatives considered

- **Hosts listening to sessions' events.** Sessions already depends on hosts (it implements `HostWork`), so hosts listening
  to sessions would be a module cycle. Ports declared by hosts and implemented by the module that owns the data (as
  `HostRatings`) keep the direction.
- **Counting a renter's stop as neutral rather than finished.** It is finished: the host ran the session until the
  renter ended it, and it can only raise the score.
- **Showing the flag to the owner.** ADR 0039 keeps the flag the admin's; "renters' reports" says enough.

## Consequences

- The weights and half-life are guesses to tune with pilot data, like the rest of the score; they are constants in
  `HostReliability`.
- A self-test the web app asks for runs the full test, including the container and speed checks, which use the GPU
  and the owner's line for a minute. Only the control plane stops it overlapping a session; the agent also refuses
  when one runs, but an offer accepted during a run is not declined. Open question: whether the agent should decline
  offers while a requested self-test runs.

## The blocklist as a signed file of its own, and a hold on payouts

### The blocklist

- **Its own signed file.** `agent/distro/blocklist.json` is still the one source, but it is no longer compiled into the
  runner. A copy ships in every distribution (`/usr/local/share/idleo/blocklist.json`), and a newer one is published as
  `stable/blocklist.json` and `.sig` in the release bucket, signed with the same Ed25519 key and flow as the app's manifest
  (ADR 0034) and with a version of its own: when it was issued (`2026-10-25T09:30:00Z`; the distribution's copy carries a
  plain date). Both sort as text, so "newer" is a string comparison in the agent, the runner and the control plane.
- **Delivery over the stream, not polling.** The control plane's release feed reads the file with the manifest, serves it
  only if the signature verifies and it is not older than the cached one, and sends `UpdateBlocklist` to an agent whose
  `Hello.blocklist_version` is older after Welcome, and to connected agents when it caches a newer one. The agent trusts it
  only if it verifies against the release key built into it and is newer than the one it holds, keeps it, and hands it to its
  runner over the runner protocol (`Request::Blocklist`) whenever the runner has an older one; the runner keeps the last
  good copy and falls back to the distribution's signed copy. A bad entry is therefore pulled within the feed's refresh (minutes) for
  connected agents and at the next connection for the rest, which is faster than the day the plan asked for. The agent does
  not poll over HTTP: it would need the control plane's REST address in its config, and the stream already reaches it.
- **Never silently weaker.** A runner that holds no list whose signature verified starts no job (`sandbox_not_ready`, the
  host's fault: nothing is pulled or made, the renter is not charged) and its self-test fails (`abuse_blocklist`). A list
  that does not verify, does not parse, or is not newer, is refused and the one in use stays.
- **The stream only delivers; the signature decides.** The agent verifies a list against the release key built into it. The
  runner does not take the agent's word: it verifies the signature again against the release key in its own image
  (`/usr/local/share/idleo/release.pub`), for a list the agent hands over, for its saved last good copy at every start, and for
  the distribution's own copy, which ships signed (`blocklist.json.sig`, made by `scripts/release/sign-distro-blocklist.sh`:
  the development key for a development image, `$IDLEO_RELEASE_KEY` for a release). Unsigned, signed by another key, and signed
  then changed are all refused, in the agent (tests/agent/blocklist.rs) and in the runner (blocklist_store.rs, the hostile
  test). The distribution's copy is a fallback only as a signed file: the image has no list the runner takes on trust. The
  distribution is 0.3.0 because the runner inside changed.
- **Learning.** The runner hashes the executable of a program whose *name* is listed, and the signal carries the hash. A name
  still only flags. When an admin confirms the review, "Add this program's hash to the blocklist" records the build, with a
  kind and the admin's reason, audited, in `trust.blocklist_additions`. The control plane never holds the signing key, so
  `export-blocklist-additions` prints the builds, `scripts/release/blocklist.sh` merges them into the file as `learned`
  entries (the review item is their source, not an https address), signs and publishes it, and the screen shows a build as
  published once the feed names it. "Take back" leaves a build out of the next file.

### Payouts

- **Held for host misconduct.** The admin chooses a cause when suspending (`host_misconduct` or `other`, stored in
  `identity.users.suspension_cause` and in the audit row). For `host_misconduct` no payout is made until the suspension is
  lifted: the weekly run, Run now, a retry and the final payout before a deletion all pass over the account, and the earnings
  wait in full. Any other suspension, and a hold by the abuse watch, leaves payouts as they were.
- **The restore drill makes real payout activity** before its backup (a payout paid, one failed and returned, a refund after a
  payout that becomes a clawback) and fails if the restored database lacks it, so `verify-ledger.sh`'s payout rules run on
  real rows.
- **A Money dashboard** in Grafana (`ops/grafana/dashboards/idleo-money.json`).
