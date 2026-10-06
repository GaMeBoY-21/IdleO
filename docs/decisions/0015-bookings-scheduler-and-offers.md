# 0015. Bookings: listings, sessions, credit holds, the scheduler and offers

- **Status:** Accepted
- **Date:** 2026-10-06

Sources:
- docs/plan/technical-design.md lines 32, 34, 83, 99–115, 197–221, 232, 329–386, 392–398, 472, 539–549, 557–568, 641, 775
- ADRs 0005, 0007, 0010, 0011, 0013, 0014
- docs/prompts/bookings-and-offers.md

## Context

Money, hosts and live agent streams exist (ADRs 0010–0014). This builds booking: everything from "a renter asks" to "a host accepts". The booking transaction reserves credit and a host together from day one (lines 354–386). Starting the job (`StartSession`, storage grants, leases), metering and settlement come next.

## Decision

### Hosts: listings and activation

1. **`PUT /v1/hosts/{id}/listing`** sets three things, owner only:
   - the batch price per minute, at least 1 paisa, in `hosts.price_listings` (one price per workload type, line 102; batch only for now);
   - the GPU temperature cap, 60–95 °C, which the agent gets in `Welcome` instead of the config default;
   - `maxConcurrentSessions`, which is exactly 1.

   One job per GPU host keeps isolation simple: one sandbox, one GPU, nothing shared. It keeps billing simple too: one live session per host, which the database's partial unique index enforces.
2. **Availability windows (line 101) are deliberately not built.** In Phase 1 an active, listed host is available whenever it's online and idle.
3. **Activation is an operator command** until onboarding's benchmark and canary job exist (line 641):
   - `activate-host` and `suspend-host` run in the `ops` profile like the credit commands (ADR 0014), audited in an append-only `hosts.status_changes`, and safe to re-run;
   - activation is refused unless the host has a valid, unrevoked certificate, a listing, and at least one reported GPU that isn't gone (docs/runbooks/hosts.md).

   The operator commands now share one runner: each module contributes named commands, and the runner picks one, with exit codes 0, 2 and 3.

### Templates

4. **`templates/catalog.yaml`, reviewed in git, is the only source of what renters can run.**
   - Every image is pinned by digest (`@sha256:`).
   - Each entry also carries the container limits a `SessionSpec` needs: memory, CPU, processes, scratch disk and checkpoint interval.
   - The build bundles the catalog into the jar, the control plane refuses to start on a malformed entry, and `GET /v1/templates` lists it.
   - The first entry is a CUDA smoke test on the base image Phase 0 pins. QLoRA follows once its image is published.
   - **cosign verification of template images arrives with the registry (lines 83, 472).**

### Sessions and their state machine

5. **`sessions.sessions` (V9)** follows lines 103–104, adapted:
   - no foreign keys across modules;
   - batch only for now;
   - the template, image digest, VRAM, the renter's maximum price and spend, and the maximum runtime;
   - `price_paise_per_min` copied from the listing at match time, so a later price change never changes a booked session;
   - `UNIQUE (renter_id, idempotency_key)` plus a request hash, so the same key with a different body is refused (422);
   - the one-live-session-per-host index, exactly as designed.

   `session_events` is append-only, enforced by a trigger.
6. **The state machine is one table of allowed transitions in code (lines 209–221).**
   - Every transition goes through one method that checks the table, bumps the version, and writes a `session_events` row in the same transaction. Anything else is refused and logged.
   - **Two moves beyond the design's table:**
     - a declined or expired offer sends a session from MATCHED back to REQUESTED for the next candidate;
     - a session no host can take goes from REQUESTED straight to NO_HOST_FREE.
   - A session whose renter's credit no longer covers the hold at match time is cancelled with reason `insufficient_credit`.
7. **The API:**
   - **`POST /v1/sessions`** needs an `Idempotency-Key`. It returns 402 if the available credit can't cover the hold the booking will need, before anything is created. That hold is 30 minutes at the renter's maximum price, capped at their maximum spend.
   - **`GET /v1/sessions` and `GET /v1/sessions/{id}`** are owner only. They show the state, the host once matched, the price, the credit held, and every transition with its reason and time.
   - **`POST /v1/sessions/{id}/cancel`** works in REQUESTED, MATCHED and PROVISIONING. It releases the hold, withdraws the offer, bills nothing, and is safe to repeat.

### Credit holds

8. **A hold is not a movement of money,** so it's not a ledger entry: it's a row in `billing.reservations` (V8), `active` until `released`, with consumption coming with settlement.
   - `wallets.reserved_paise` always equals the sum of the user's active holds, changed in the same transaction. Only `Ledger` writes `billing.wallets`, so the hold service moves `reserved_paise` through it.
   - A trigger allows only one change to a hold: active to released.
   - **One active hold per session, not one ever:** a session can be re-matched after a decline, so it may have several holds over time. The uniqueness is a partial index on active holds.
   - The nightly reconciliation now also checks every wallet's reservation against its holds.
9. **`CreditReservations.reserve` and `release` are `MANDATORY`:** they only run inside the caller's transaction, because a booking is one atomic unit (line 32). The size is 30 minutes at the session's price, capped at its maximum spend (line 392).

### The scheduler

10. **Triggering by durable events.**
    - `sessions` publishes `SessionRequested` and `SessionCancelled`; `scheduling` listens.
    - Spring Modulith's JDBC event publication registry records each publication in the same transaction as the change. A listener that fails leaves it incomplete, and a scheduled job hands incomplete publications back to their listeners. A crash between the commit and the scheduler loses nothing; a test fails the first listener attempt and watches the retry match the session.
    - Flyway creates `modulith.event_publication`; Modulith's schema creation stays off.
    - Listeners run asynchronously, on Spring Boot's virtual-thread executor.
11. **Candidates (line 333)**, each fact from the module that owns it:
    - **hosts:** active, listed for batch at or under the renter's maximum price, with a non-gone GPU big enough;
    - **agent-gateway presence:** online, not `update_required`, owner away, every GPU below the host's cap;
    - **sessions:** no live session;
    - and not already offered this session.
12. **Score (lines 340–350), batch weights:** price 0.3, reliability 0.35, latency 0, bandwidth 0.15, cache 0.2.
    - **P_max** is the renter's own maximum price.
    - **The data doesn't exist yet**, so reliability (no host score) and bandwidth (no benchmark) default to 0.5, and cache (no inventory) to 0. These are named constants in `Scoring`, starting guesses to tune with pilot data. With them, price alone orders candidates for now.
    - **Ties** are broken by host id, so ranking is deterministic.
13. **The booking transaction (lines 354–386)** is tried for each candidate in score order, each in its own short transaction:
    1. lock the host's row through the hosts API (`FOR UPDATE SKIP LOCKED`, still active);
    2. move the session REQUESTED to MATCHED, guarded by its version;
    3. hold the credit;
    4. insert the offer and its agent command.

    If any step changes nothing, that candidate's transaction rolls back and the next is tried. The one-live-session index is the second guard. No candidate, or three offers without acceptance (line 214), means NO_HOST_FREE, with the hold released.
14. **Metrics:**
    - `scheduling.match.duration`
    - `scheduling.match.candidates`
    - `scheduling.match.outcome` (matched, no_host_free, session_moved, insufficient_credit)
    - `scheduling.no_host_free`
    - `scheduling.offers.answered`

### Offers and the agents' command queue

15. **`agentgateway.agent_commands` is the one durable queue for everything sent to agents (lines 34, 115).**
    - It adds `message_id` (reused on every resend), `requires_ack`, `expires_at` and `sent_at`.
    - **Delivery:**
      - after commit, a hint goes to the Valkey channel of the instance whose presence record holds the host's stream;
      - that instance sends the host's pending, unexpired commands in creation order, under `FOR UPDATE SKIP LOCKED`;
      - the same happens right after `Welcome`, and every few seconds for every connected host.
    - **The table is the truth; pub/sub is a hint.** Valkey being down doesn't stop an instance from starting (ADR 0011): the subscription starts in the background and retries, and the sweep delivers meanwhile.
    - **Acks:** a command needing an ack is resent until the `Ack` arrives. An offer doesn't need one: it's answered by `OfferReply`, expires, and is never sent after expiry.
16. **`scheduling.offers`** holds up to three per session, each open 10 seconds (line 232), with its status and decline reason.
17. **Replies come through a port.** The agent gateway declares `OfferReplies` and the scheduler implements it, so the dependency points one way, and the modularity test stays green. The gateway acks a reply only after the scheduler has recorded it; otherwise the agent resends.
    - **Accepted in time:** MATCHED to PROVISIONING.
    - **Declined:** in one transaction, the offer is closed with its reason, the hold released, and the session sent back to REQUESTED (publishing `MatchRequested`, durable like the others), or to NO_HOST_FREE after the third offer.
    - **Expired:** a sweeper takes pending offers past their expiry with `SKIP LOCKED` and treats them exactly as declined with reason `expired`. It's safe on several instances.
    - **Late, unknown, or for a withdrawn offer:** acked and ignored. A cancellation withdraws the session's open offer and its undelivered command.

### The provisioning timeout

18. **A session in PROVISIONING that hasn't reached RUNNING within 3 minutes** (configurable) fails with reason `start_timeout`, nothing billed, hold released (line 216).
    - The sweeper uses `SKIP LOCKED`, so it's safe on several instances.
    - **Until the next piece of work sends `StartSession`, every accepted session ends this way. That's intended and tested.** The same guard protects production from agents that accept an offer and never start it.

### The agent answers offers

19. **The agent decides within the offer's `expires_in`, counted from receipt (ADR 0007).** It accepts only if all of these hold:
    - it isn't shutting down;
    - it's under its session limit;
    - the owner is away;
    - every GPU is below the policy's temperature cap;
    - a GPU has enough memory.

    Otherwise it declines with the matching `OfferDeclineReason`. An offer that had already expired when the agent looked at it gets `EXPIRED`. The decision is a pure function, unit-tested for every reason.
20. **The agent keeps its `OfferReply` until the `Ack`,** resending it with the same message id every 3 seconds on the same stream. A stream drop drops it; the offer will have expired anyway.

## Deliberately not built yet

- `StartSession`, storage grants, leases, usage, metering, settlement and charges.
- Availability windows, the host score, bandwidth benchmarks and cache-aware scoring (defaults stand in).
- cosign verification of template images.
- `/stop` for running sessions, and the SSE events stream.

## Alternatives considered

- **One booking transaction for all candidates:** rejected. A failed candidate would mark the whole transaction rollback-only; a short transaction per candidate keeps locks brief, as the design intends.
- **Holds as ledger entries:** rejected. A hold moves no money, and settlement would have to reverse every hold; a separate, checkable table plus `reserved_paise` is exact and simpler.
- **A plain in-memory event, or `@Scheduled` polling for new sessions:** rejected. An in-memory event is lost on a crash; polling adds latency and load. The registry gives at-least-once delivery, and the matcher is safe to run twice: its version guard and state checks make a second run change nothing.
- **Only pub/sub for commands:** rejected. A message published while the instance restarts is lost. The table plus a sweep can't lose a command.
- **The scheduler calling the agent gateway to deliver replies:** impossible without a module cycle. Hence the port.

## Consequences

- A renter can book, a host's real agent accepts, and the credit is held exactly. The end-to-end test does it on every change.
- No host is ever booked twice. Tests check, in consistent snapshots, that every hold matches its wallet, under 20 concurrent bookings against 3 hosts and with two schedulers racing for one host.
- Every booked session currently ends in `start_timeout` until `StartSession` exists.
- The score's defaults mean the cheapest suitable host wins for now.
