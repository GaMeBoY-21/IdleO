# 0056. The control room

- **Status:** Accepted
- **Date:** 2026-11-08

## Context

The console (ADR 0027, 0051 to 0055) lets an admin find anything, work queues and run the money. It can't yet stop the
bleeding in an incident: there is no switch to stop new bookings, payouts or top-ups without a release, and no way to make
the app read-only for maintenance while the money keeps flowing. A switch that only hides a button stops nothing: the
API, the CLI and the SDK go on.

## Decision

### The flags

One `flags` module, and one list: the kernel's `Flag`. Each flag says:

- who owns it;
- what pausing it does;
- its safe value if the flags can't be read;
- whether a change emails the Owners.

| Flag | Pausing it | Safe value | Emails the Owners |
|---|---|---|---|
| `bookings.new` | Refuses new bookings; booked sessions still start | running | yes |
| `sessions.start` | Refuses new bookings, and holds waiting ones: no offer goes out, and none gives up while it lasts | running | yes |
| `payouts.send` | Payouts are made but wait, created, until it runs again; RazorpayX's webhooks still land | **paused** | yes |
| `topups` | Refuses a new top-up; a payment already made is still credited | running | yes |
| `signups` | Refuses someone signing in for the first time | running | no |
| `hosts.link` | Refuses the app's link requests and their approval, adding a host and new enrolment codes | running | no |
| `images.custom` | Refuses renters' own images, submitted or booked | **paused** | no |
| `maintenance` | Makes the app read-only (below) | running | yes |

The safe value is paused only where a wrong "running" could cost money or let something risky through unseen: payouts
can wait without harm, and an unchecked image shouldn't run because the database blinked. Everything else stays
running, so a flag that can't be read never closes the business.

**Every flag is enforced on the server**, where the thing it stops happens:

- the booking transaction;
- the matcher and its re-match loop;
- `PayoutService.send`;
- the top-up order;
- the sign-in that would create an account;
- the link endpoints;
- `ImageService`.

A refusal is the catalog's `paused` problem: "Bookings are paused: we expect to be back by 8 Nov, 4:00 pm India
time.", with `flag` and `Retry-After`. The app shows it as a banner. **No flag ever stops a running session.**
`FlagEnforcementCoverageTests` fails on a flag without a test marked `@TestFlags.Enforces` with it.

### Reading them

The database (`flags.flags`) is the truth. Each instance keeps a copy, and reading never waits on the database:

- a change commits with its audit row and the Owners' notice;
- then a hint on the Valkey channel `flags:changed` makes every instance read again at once (measured: 42 ms to the other
  instance);
- every instance also reads every 30 s whatever Valkey says, so a lost hint costs at most that.

A failed read keeps the last good copy for two minutes, measured on the monotonic clock, never the business clock.
After that, or before any read has worked, each flag is its safe value. A pause with an end that runs again by itself
is over at its time in every instance's copy, and a 15-second sweep (safe on both instances) writes it back with a system
audit row.

### Changing them

Permissions are `flags.read` and `flags.change`, which is sensitive and so needs a factor in the last five minutes.

- **Pausing** is immediate, with a reason, and optionally until a time. It is never gated: stopping is never gated
  (ADR 0051).
- **Running again** goes through `Approvals` as `flag.resume`, so another admin with `flags.change` approves it. With no
  one else, it runs **at once**, self-approved, and the Owners are told: the new `Cooling.NONE`.

**Kill switches skip the cooling-off.** Money and access wait out a solo delay because a rushed one can't be taken back.
A kill switch is the other way round:

- the harm of waiting is the outage itself, which an hour's cooling-off would make an hour longer;
- running a switch again only puts back what was there before the incident;
- if it was wrong, pausing again is immediate.

So the safety it keeps is a second pair of eyes when there is one, the reason, the audit row and the Owners' email,
never a delay.

### Maintenance mode

Maintenance mode is the `maintenance` flag. While it's on, every `/v1` write is 503 `maintenance` with Retry-After,
taken from the expected end. Reads go on. It's decided after routing, by the handler reached (`MaintenanceMode`), never
by the path's spelling. These keep working, each a handler marked `@OpenDuringMaintenance` (`MaintenanceModeTests`
lists exactly these):

- Razorpay's and RazorpayX's webhooks, and Resend's email events: money and mail must never be lost;
- signing in: first sign-in is a read, and the command line's device sign-in is marked open;
- a renter stopping or cancelling their session: it only ever costs them less;
- cancelling a scheduled deletion: the deletion would otherwise go ahead while they can't stop it;
- confirming a top-up already paid for: the money is already ours.

Some things aren't `/v1` writes at all, so maintenance never touches them:

- the admin console (`/admin/v1`);
- the scanner's internal calls;
- the agents' streams, running sessions and metering (gRPC).

`GET /v1/public/status` (no sign-in, cached 10 s) says what's paused and when it's expected back. It gives the flag's
own words, never an admin's reason. The app's frame shows it as a banner after the first screen, and at once when a
request meets a pause. The website's footer shows it in a status line.

### Presence history

The agent gateway, which alone writes presence (ADR 0011), records each host's changes in
`agentgateway.presence_changes`: online, offline or unknown, with when, and never an address. They're kept 90 days
(`retention.periods.presence-changes`), are in the person's export under hosting, and are deleted with their records.

- A stream opening is always an online row, so a reconnect counts even when the new stream replaced the old before the
  old one closed.
- A close that still owned the record is offline.
- A 30-second sweep, on one instance at a time, catches a record that lapsed with no close (offline). It also catches
  Valkey not answering: a host recorded online becomes unknown until presence can be read.
- Every other change is written only when it is one, and only if nothing newer is recorded
  (`agentgateway.presence_states`), so a late observation never writes over a newer state.

The host page's **Presence history** tab reads it, with the streams opened each day.

### The live dashboard

The control room replaces the first dashboard (ADR 0027): one dashboard, not two. Its permission is
`ops.dashboard.read`, which every role that had `dashboard.read` was given. The first dashboard's figures became health
checks in the modules that own them.

**It's built from rollups, never from queries per page view:**

- each module implements the kernel's `ControlRoom` part it owns, from its own tables;
- one job (`ControlRoomRollups`, under `OneAtATime`) asks them and keeps each part in `console.rollups`: the health
  checks every 10 s, everything else every minute;
- `GET /admin/v1/control-room` reads the rollups;
- `GET /admin/v1/control-room/stream` sends the same every 10 s as server-sent events;
- a part that fails keeps its last rollup, and the page says when each was worked out, marking it stale after two missed
  turns.

| Panel | From |
|---|---|
| Money: GMV, take, refunds, chargebacks, for today, 7 and 30 days | billing, by the money reports' own code (ADR 0055), so they're the same figures; only for an admin who also holds `money.read`, decided on the server |
| Marketplace: running, waiting to match, booked today, time to match (p50, p95), starts that ran, the top five failure reasons | sessions |
| Supply and demand: hosts online by GPU model, utilisation (busy over online GPU-hours, 24 h), heatmaps by model and hour of day in India (7 days) | the agent gateway's presence history for supply, sessions' attempts for demand; the console divides them |
| Funnels: renters signed up, first top-up, first and second session; hosts linked, self-test passed, first session, first payout | each step from the module that owns it: everyone who ever reached it |
| Health: database, Valkey, backup age, agent streams, scanner, settlement and wallet reconciliation, settlement backlog, review queue, inbox targets | each check from its module; alerts stay in Grafana, linked by the build's `VITE_GRAFANA_URL`, with no Grafana token anywhere in the control plane |

Every figure links to the list behind it.

### The fleet

`GET /admin/v1/fleet` lists every host on the one list engine (ADR 0052), so it filters, saves and exports like every list:

- agent version;
- distribution version, as its last self-test said;
- largest GPU and its driver;
- self-test pass rate over 30 days;
- the signed blocklist it holds, now recorded from each Hello;
- when it was last seen ("not for a day" is a filter).

Reconnects a day come from the presence history through the hosts module's `HostConnections` port, which the agent
gateway implements. `/admin/v1/fleet/summary` counts the whole fleet the same ways, and names the stragglers behind the
release the feed holds. "Notify these owners" sends the existing notice (ADR 0037) to the owners listed, and more than
20 people goes to another admin first.

### Staged rollouts

The signed manifest's `rollout_percent` stays the ceiling and the agent's checks of the signature don't change (ADR
0034). The control plane only decides who is offered the release:

- **Starting:** a newly cached release starts at 5% of hosts (`hosts.agent_rollouts`, audited as the system).
- **Who:** a host is offered it while its bucket (SHA-256 of host id and version, modulo 100) is below the share, so the
  choice is stable and widening only adds hosts.
- **Widening:** to 25, 50 or 100% is `rollout.widen` through `Approvals`, with a factor (`rollouts.widen`). With no one
  else to approve it, it runs at once, as running a kill switch again does, and for the same reason.
- **Pausing:** immediate (`rollouts.pause`). Narrowing is refused: pause instead.
- **Reaching agents:** each instance's feed notices a changed share at its refresh and offers it to the agents it holds;
  the instance that widened offers at once.

**The automatic pause.** Every 5 minutes, on one instance, the release's hosts (those already on it) are compared with
the rest over 24 hours. A rolling release pauses itself, audited as the system, and the Owners are told, when either:

| Signal | Threshold (`hosts.rollouts.*`) |
|---|---|
| Sessions lost to the host | at least 5 of its hosts' sessions, and a lost share more than 10 points above the rest's |
| Reconnects a host a day | at least 3, and more than twice the rest's |

### Broadcast notices

The written notice (ADR 0037), for many (`broadcasts.send`):

- **To:** every renter, every host's owner, the people carrying a tag, or the owners whose agent runs a version in a
  range.
- **When:** at a time, in the app always and by email when asked.
- **Approval:** every broadcast reaches many, so each is an approved request, as a notice to more than 20 people is.
- **Sending:** claimed once when it's due, and each person is told once for it. It's logged with how many it reached,
  never who by address. One not sent yet can be cancelled.

## Alternatives considered

- **Flags in Valkey only**: fast, but Valkey holds only what may expire (ADR 0011) and is never in readiness. A restart
  would silently run everything again.
- **The dashboard's queries on each page view**: cheap on a seed, not on a year's sessions, and every admin's tab would
  ask again every 10 s. One job per minute costs the same however many admins watch.
- **Two dashboards, the first one kept**: two places for the same figures drift apart.
- **A third-party flag service**: another outside dependency for eight switches. It would also put a kill switch behind
  someone else's outage.
- **A cooling-off for running a switch again, as for money**: rejected above; it lengthens outages.
- **Maintenance by scaling the API to zero**: it would drop the webhooks and the agents' streams, which is exactly what
  must keep working.
- **Holding a path list for maintenance**: a list of path spellings drifts, and an encoded path slips past it. The
  annotation on the handler is the one place.

## Consequences

- A new flag is a value of `Flag`, a migration adding its row, its enforcement, and a test marked with it.
- A new `/v1` write is refused in maintenance unless it's marked open, and the test listing the open ones changes with
  it.
- The flags' copy is per instance by design. Anything that must agree across instances reads the database.
- The rollout's thresholds are first guesses with a handful of hosts; they are settings so they can be tuned with real
  numbers.
- Open question: whether Finance alone should be able to pause `payouts.send`. Today any holder of `flags.change` may
  pause any flag.

## Addendum: unmet demand, cohorts and the stream's proof

**Unmet demand.** A booking that ends without a host (`NO_HOST_FREE`, from no candidate or from offers running out) is a row of `sessions.unmet_bookings`, written in the transition's own transaction. A booking names the memory it needs and never a GPU model, so what was asked for is a memory class (`GpuClasses`: 8, 12, 16 and 24 GB; a need above them is its own row). The heatmap's rows are those classes, not models: asked, served and missed are counted from one booking, so a row adds up, which a model served beside a class asked could not. Asking bookings to name a model would be a change to what renters book, and isn't made here. Each figure opens the sessions list on the same class, hour and 7 days, by `demandClass`, `demandHour`, `served` and `state`.

**Cohorts.** Everyone who signed up in a week is that week's cohort, for the last 12 weeks. Each step counts those of them who reached it within 7 and within 30 days of their own sign-up. A host's steps count from the same sign-up, so a share is always of everyone who joined that week. Each module says who reached what (`FunnelSource.reached`); the rollup keeps only counts, every 10 minutes. A figure whose window hasn't closed says "so far". The all-time funnels stay as the second tab.

**The stream is proven over a real port.** `ControlRoomStreamOverHttpTests` reads it with a streaming HTTP client: events 9,981 and 10,011 ms apart, with the connection open. MockMvc never showed them: `SseEmitter.send` returned without an error and `MockHttpServletResponse` stayed empty and uncommitted, so the fault was in how the test looked, not in the stream. The page therefore polls only after the stream has been lost, and stops as soon as an event arrives.
