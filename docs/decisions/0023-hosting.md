# 0023. Hosting: pause, disk kept free, removal, telemetry, a price band, and earnings read from the ledger

- **Status:** Accepted
- **Date:** 2026-10-10

Sources:
- docs/plan/product-surface.md sections 3.3, 11.8 to 11.10 and 12
- docs/design/hosting/*.html, the targets for the five hosting screens
- ADRs 0011 (the agent stream), 0013 (ports hosts declare), 0015 (scheduling), 0017 (charging), 0019 (the booking contract), 0021 (requesting a GPU like a ride), 0022 (hosts with room)

## Context

Until now a host was something an operator set up: its owner could register it, get a code, set a price and read a balance, but nothing on screen said whether it was earning, nothing let them step away from it, and nothing let them take it back. The web app had no hosting screens, and four things the screens need had no API: pausing, a way to keep disk for themselves, a way to remove a machine, and what a machine is doing now. Two more needed a rule: what price to suggest, and where an owner's earnings come from when they own several machines.

Constraints that shaped every choice:
- **Modules.** `hosts` may not depend on `sessions`, `scheduling`, `billing` or `agentgateway` (ADRs 0013, 0015), yet a host's screen shows its running session, its temperature and what it earned.
- **Money.** Earnings are the ledger's (ADRs 0014, 0017). Whatever an owner reads must equal the ledger, to the paisa, and a settlement rounds the host's share down in each 15-minute window.
- **Privacy.** A host's owner never learns who rented their machine, what they ran or with what (principle 5 of the product surface).
- **The scheduler's filters live in one place,** `CandidateFinder` (ADR 0019).

## Decision

**Pause is a time, not a flag.**
- `POST /v1/hosts/{id}/pause` takes an optional `until`, which must be in the future and within 7 days (`pause-too-long` otherwise); `POST .../resume` clears it.
- A paused host gets no new offers; a session it's running finishes.
- A host is paused from `paused_at` until `paused_until`, or until it's resumed when that's empty; whether it's paused now is worked out from the time, with the injected clock. A pause that ends by itself therefore needs no job, and a host can never be stuck paused by a sweep that didn't run.
- `CandidateFinder` adds "not paused" to its hard filters, so matching, quotes and availability agree. A paused host is also left out of availability, so the price range a renter sees ignores it.
- The web app offers four durations: until I resume, 1 hour, until tonight (6 pm in India, offered only while it's more than an hour away) and until tomorrow morning (8 am). All are far inside the 7 days.

**An owner keeps disk for themselves.** `keepFreeGb`, 10 to 500, default 20, is part of the listing. A host is offered a session only when its free disk less `keepFreeGb` still covers the session's files and scratch space (ADR 0022's rule, now with the owner's share taken off first). It lives in the same filter, `CandidateFinder`.

**Remove is for good, and keeps the row.**
- `DELETE /v1/hosts/{id}` is refused with 409 `host-busy` while the host has a session that hasn't ended. Otherwise the host's status becomes `removed`, with the time, its certificates are revoked, its enrolment code is deleted, an audit row is written, and `HostRemoved` is published.
- The row stays, because the ledger points at it. A removed host never comes back: its owner adds the machine again, which makes a new host. From then on it answers 410 `host-removed`, and it leaves the lists.
- The remove, a booking and the host's lock take turns under the host's row lock, so a session can't be matched to a host being removed.
- The agent gateway closes the stream at once on the instance that holds it (`RemovedHosts`, on the event). Another instance's stream closes at the next heartbeat that records the agent as seen, which is once a minute (`recordSeen` now says whether the host was removed), and the revoked certificate stops it coming back.

**Ports keep the module directions.** `hosts` declares what it needs and others implement it, as it does for `HostPresence` (ADR 0013):
- `HostWork`, implemented by `sessions`: whether the host is busy, and the session it's running now (service, running label, when it started and what the host has earned from it so far; never the renter).
- `HostTelemetry`, implemented by `agentgateway`: the latest GPU readings and the last hour.
- `HostPresence.connections`: since when the agent has been connected, and whether it must update.
- Earnings go the other way: `billing` declares `EarningDetails` (the service and minutes behind a ledger entry) and `metering`, which already depends on billing and sessions, implements it. `billing` also gives `sessions` a `HostEarnings` to read what each session paid a host.

**What a machine has earned so far is the ledger's, plus the host's share of what's accrued.** `currentSession.earnedSoFarPaise` is what the ledger holds for that session on that host (settled windows, net of any giveback) plus the host's share of what metering has accrued but not settled, split the way a settlement splits it (the platform's commission, the host's share rounded down). Once the window settles, the figure equals the ledger's.

**Telemetry is a ring in Valkey, for the owner's screen only.**
- The agent gateway keeps the last 60 points per host, one a minute, in a Valkey list written by a script from heartbeats (so two instances can't both write the same minute), trimmed to 60 and gone an hour after the last heartbeat.
- `GET /v1/hosts/{id}/telemetry` returns the latest reading (null while the agent is offline) and those points.
- It is never read for billing or for scheduling: if Valkey is down the screen has no history, and nothing else changes.

**The price band is scheduling's.** `GET /v1/hosts/{id}/price-band` returns `{ lowPaisePerMin, highPaisePerMin, sampleSize }` from the listed prices of the other active hosts with the same GPU model, leaving out the top and bottom 10%. With fewer than 3 such hosts it returns a default for the GPU's memory class (8, 12, 16 or 24 GB) from `scheduling.price-band-defaults`, and `sampleSize` is 0. It's in scheduling because it reads every listed host's price, and `hosts` may not depend on scheduling (ADR 0019). Prices move in steps of 1 paisa a minute, exactly ₹0.60 an hour, so the screen shows rupees an hour and the API paise a minute, always whole numbers.

**Owner-wide earnings are read only from the ledger.**
- `GET /v1/earnings` sums the `host_payable` entries of every host the person owns, removed ones too: the total, this week (Monday to Sunday, India), the minutes rented this week, each of the last 7 days, each machine, and a paged history. A lost-time giveback is a negative entry, labelled "Given back for lost time".
- Nothing is recomputed from sessions, so it always equals the ledger: the end-to-end run checks it against a sum of the ledger's entries.
- The ledger has only amounts. The service and minutes beside each entry come from the `EarningDetails` port, and for that `metering.settlement_parts` now keeps each host's minutes (V23).
- Payouts aren't built: the response says `payoutsStart: "paid-pilot"`, and the screen says so.

**A host sees sessions, never renters.** `GET /v1/hosts/{id}/sessions` lists the attempts that ran on a machine: service, when, minutes (a started minute counts whole, as charging does), what it earned and how it ended (running, completed, stopped, failed, or lost with what was given back). No endpoint, and no field, names a renter, their inputs or their parameters; tests read every response for the renter's id.

**The web app** (web/DESIGN.md "Hosting"): a Hosting tab (a fourth tab on a phone; a pill on a laptop), one machine, its price and limits, the sessions it ran, earnings, and a four-step Add a machine. A machine's figures are read again every 5 seconds while it runs a session: the money changes in place when the server says, never counted up (web/DESIGN.md principle 4), and the elapsed time ticks on the app's clock. The owner's word for their own host is "machine"; renters never see "host" or "machine".

## Alternatives considered

- **A `paused` flag and a job that clears it.** Lost: a job that doesn't run leaves a host paused for ever, and it needs a clock of its own. A time is always right at the moment it's read.
- **Delete the host's row on removal.** Lost: ledger entries, status changes and sessions point at it; earnings must survive it.
- **Let `hosts` read sessions and presence directly.** Lost: it breaks the module directions the modularity test enforces. Ports cost a small interface each and keep every dependency one way.
- **Compute earnings in `billing` from sessions.** Lost twice: billing can't depend on sessions, and a second computation would one day disagree with the ledger. The ledger is read, and only labelled from elsewhere.
- **Put the price band in `hosts`.** Lost: it needs every listed host's price, which `scheduling` already reads with its own filters (ADR 0019).
- **Telemetry in PostgreSQL.** Lost: a point a minute for every host for ever, for a screen that shows an hour. Valkey is allowed to lose it (CLAUDE.md), and the screen says so when it does.
- **Count the host's earnings up on the client every second.** Lost: it would be an estimate of money shown as fact, which web/DESIGN.md forbids; reading the server's figure every 5 seconds is honest and costs one small request.
- **Mirror the commission through the API.** Open: the web app mirrors the 20% as one constant with a test (as `vram.ts` mirrors the GPU memory rule), so a change to `billing.commission-percent` needs the same change in `web/src/lib/hosting.ts`. The API could send it.

## Consequences

- An owner can see whether a machine is earning, step away from it, set a price against what others charge, and take it back, from a phone.
- A removed host is a row that stays: queries over hosts must say whether they mean removed ones (the owner's earnings do; lists and the scheduler don't).
- The default free disk of 20 GB means a host reporting less than that plus a session's files is never offered work. The end-to-end run lists its hosts with 10 GB so a small CI disk still has room.
- The history in `GET /v1/earnings` has one entry per settlement window (15 minutes), not one per session, because that's how the ledger is written: a 2-hour run is eight entries. If that reads badly in use, the screen can group by session; the API stays the ledger's.
- The suggested price defaults (8 to 14, 12 to 20, 16 to 28 and 24 to 42 paise a minute for the 8, 12, 16 and 24 GB classes) are starting guesses. Watch them against real listings once there are some.
- Open question: whether an owner may remove a machine that's paused with a session finishing (today it's refused until that session ends).
- Open question: availability windows, allowed workloads, the host score, the installer and tray app, host emails and benchmarks are not built (P2 and later); the add-machine screen's install step links to the runbook until there's an installer, and its checks step is "pending" until an operator activates the host.
