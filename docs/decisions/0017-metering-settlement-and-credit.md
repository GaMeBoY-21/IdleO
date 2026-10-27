# 0017. Metering, settlement and credit: two witnesses, fifteen-minute windows, leases inside the hold

- **Status:** Accepted
- **Date:** 2026-10-07

Sources:
- leases never outlast credit), "Metering and billing", "The settlement transaction", the `usage_minutes` table, "Failure handling" (host loss), the REST rows for `/stop` and host earnings
- proto/gpu/agent/v1/session.proto (`UsageReport`, `StopSession`, `RefreshCredentials`); proto/README.md
- ADRs 0005, 0007, 0014, 0015, 0016

## Context

ADR 0016 made sessions run, end and survive a lost host, but charged nothing: an ended session released its hold. This decides how a minute becomes money:
- which minutes are billable, and who says so;
- when they are charged, exactly once;
- how the hold stays ahead of the job, so no lease ever outruns the renter's credit;
- what happens when credit or the spending cap runs out, when a host is lost, and when a renter stops a job.

Money rules from ADR 0005 and ADR 0014 stand: integer paise, `Ledger.post` only, balanced and append-only, a nightly check.

## Decision

### The first witness: the agent's minutes

1. **The agent meters, not the runner.** From the attempt's RUNNING, on the lease clock (ADR 0016), the supervisor sends one `UsageReport` per whole minute:
   - `minute_index = minute_index_base + n`;
   - `gpu_seconds` at most 60;
   - `avg_util_pct`, the mean of the telemetry samples in that minute.

   When the job ends it reports the partial last minute. After its lease lapses it reports only minutes that ended before the lease did, and no partial one.
2. **Kept until acked.** Reports go through the status queue and are resent on reconnect until acked. **The queue is in memory:** an agent that crashes or is killed loses the minutes it hadn't sent. Those minutes then have only the lease witness, so they're never charged (rule 4). That errs toward the renter, and it's why the host-loss scenario counts on minute 0 being acked before the kill.
3. **The control plane records a report only from the session's current attempt on its current host.** Anything else is acked and ignored (ADR 0007). Out-of-range values are acked and dropped, never stored:
   - a negative index or one before the attempt's base;
   - an index for a minute that hasn't started yet on the control plane's clock;
   - `gpu_seconds` outside 1–60 or utilisation outside 0–100.

   Each outcome is counted in `metering.usage.reports{outcome}`. A report for a session whose metering has closed goes to the review queue as `late_report`.

### The second witness: the control plane's lease record

4. **`lease_alive` comes only from `sessions.lease_coverage`,** the stretches the control plane granted:
   - one stretch per StartSession delivery and per LeaseRenew sent, from the send time to send time plus the TTL;
   - nothing the agent says.

   **A minute is billable only when both witnesses saw it:** `agent_reported AND lease_alive`, from the same attempt.

   **How coverage is decided at the edges:**
   - An attempt's minute *k* is [R + k·60 s, R + (k+1)·60 s), where R is the attempt's first RUNNING as the control plane received it. The agent's clock never enters.
   - The minute is alive only if **one unbroken stretch** covers all of it. A renewal sent after the previous lease ran out starts a new stretch, so the gap is never covered, and a minute containing it isn't alive.
   - The control plane writes a row for every whole minute it saw the attempt running.
   - The partial last minute exists only if the agent reports it. It's alive if a stretch covers it from its start to the attempt's end (`attempts.ended_at`, when the control plane learned the attempt stopped).
   - Rows land in `metering.usage_minutes` keyed by session, index and minute start.
5. **The partial-minute rule, in renter terms: a started minute is charged as a whole minute.** 150 seconds of running is three minutes. The rule applies only to a minute both witnesses saw.
6. **`usage_minutes` is partitioned by month.** The migration creates the current UTC month and the next three. A daily job (03:15, under an advisory lock) keeps three months ahead.

### Settlement

7. **When.** Every 15 minutes from the session's minute 0, and once more at its end. The metering tick runs on every instance; each session is handled under `select … for update skip locked` on its `metering.metered_sessions` row, so one instance settles it at a time.
8. **What.** A settlement, in one database transaction:
   - claims the billable, unbilled, unwaived minutes before the window's end, in index order;
   - charges each at its attempt's price: amount = minutes × price;
   - splits each host's part: host share = floor(part × (100 − c) / 100), commission = the rest. c is `billing.commission-percent`, 20 by default; the plan tests 15–25.
   - posts one balanced ledger transaction: debit the renter's wallet, credit each host's `host_payable`, credit `platform_revenue`;
   - records the settlement and each host's part (`metering.settlements`, `metering.settlement_parts`).
9. **Exactly once.** `txn_id` is a name-based UUID of `session-settlement:{session}:{n}`, where n is the session's settlement count plus one. A crash rolls back everything, claims included, so the retry settles the same window under the same id. A window already settled claims nothing. Billing refuses to shrink a hold twice for one `txn_id`.
10. **Never past what the session can pay.** Settlement stops claiming at the smaller of:
    - the spending cap less what has been charged net;
    - the hold plus the renter's available balance.

    So the wallet never goes negative; the database refuses it anyway. Minutes beyond the limit stay unbilled and go to review as `over_limit`.
11. **Closing.** A session closes once it has ended and either every minute either witness saw is decided, or `metering.review-after` (5 minutes) has passed. Closing means:
    - a final settlement;
    - minutes only one witness saw go to review;
    - the hold is released.

    A session that never ran releases its hold at once, as before.
12. **`GET /v1/sessions/{id}`** gains `chargedPaise` (settled, less refunds), `accruedPaise` (billable but unsettled, at price) and `lowCredit`.

### Holds and credit

13. **Every hold change is audited.** `billing.reservation_changes` is append-only (triggers refuse updates, deletes and truncation) and records `reserved`, `settled` (with the settlement's `txn_id`), `topped_up` and `released`, each with the amount before and after.
    - The order is fixed, under the renter's wallet lock: shrink the hold by the charge, post, top up. The wallet's reservation then never exceeds its balance at any step.
    - The nightly check now also proves that every hold's changes add up to its amount (`HOLD` mismatches).
    - `reconcile-wallets`, an operator command, runs the same check on demand. It exits 0 when everything matches, 4 when anything doesn't, and 3 if another instance is running it.
14. **Top-up.** After each settlement the hold returns to 30 minutes at the current attempt's price, limited by:
    - what's left of the spending cap;
    - the renter's available balance.
15. **A lease never outruns the credit.** The rule: renew only if hold − committed ≥ price × the minutes the new lease would let the attempt start beyond its current one (`MeteredSessions.minutesALeaseStarts`).
    - Committed is the cost of minutes both witnesses saw that aren't settled, plus the current attempt's started minutes that aren't decided yet.
    - Each metering tick writes committed to the hold (`committed_paise`).
    - Lease renewal checks the rule before every LeaseRenew. An unrenewed lease lapses and the agent stops (ADR 0016). `sessions.leases.not_renewed` counts these.
16. **Before that happens, settle early.** When the hold, less committed, can't cover a lease plus one more minute, the hold isn't limited by the cap, and the renter has more available, metering settles now; the top-up then refills the hold.
17. **Running out.** When the hold can't cover the next lease, metering stops the session with `StopSession`:
    - `checkpoint_first`, with 60 s of grace;
    - reason `max_spend_reached` when the cap is what limits the hold, `credit_exhausted` otherwise.

    The agent gets the same reason: `STOP_REASON_MAX_SPEND_REACHED` or `STOP_REASON_CREDIT_EXHAUSTED`. The first was added to protocol v1 before any agent was released, while v1 was still open (ADR 0007), so no released agent can meet an unknown value. The session goes FINISHING. The agent writes its checkpoint within the grace, and its last lease, bought while the credit covered it, bounds how long it runs. The session ends COMPLETED with reason `stopped`. In `CreditFlowTests`, with an injected clock, a session with exactly 60 paise ends at a balance of exactly 0.
18. **`low_credit`** is set while the hold plus the available balance covers under 10 minutes. It's a flag on the session only; no notification yet.

### Stopping a running job

19. **`POST /v1/sessions/{id}/stop`**, owner only, body `{"checkpointFirst": true}` by default:
    - **Running session:** moves RUNNING → FINISHING (`renter_stopped`) and queues `StopSession` with 60 s of grace. When the agent reports STOPPED, the session ends COMPLETED (`stopped`). If the agent never reports, a sweep completes it after the grace, the lease and the transit allowance have passed.
    - **Repeat call:** returns the same state and queues nothing more.
    - **Session not yet running:** a 409 problem pointing at `/cancel`.

### Fresh credentials for long jobs

20. **`RefreshCredentials` is queued when an attempt's grants pass half their lifetime,** one at a time and acked.
    - Its credentials are minted at each delivery, like StartSession's, so none is stored.
    - A host that was away gets it right after it reconnects, with its other queued commands.
    - The agent swaps the grants in the running job without restarting it.
    - The test profile uses 4-second grants, the end-to-end test 60-second ones. MinIO's STS refuses anything under 15 minutes, so its keys last at least that, while the agent stops using a grant once its `expires_in` has passed.

### Minutes lost with a host

21. **Waivers.** When an attempt is lost (HOST_LOST), its minutes ending after its newest complete checkpoint are waived. With no checkpoint, they're waived from its first RUNNING.
    - **The checkpoint's time** is when storage finished writing its `_COMPLETE` marker, on storage's clock (`sessions.checkpoints.completed_at`). Storage is listed, so a checkpoint finished just before the host died counts even though it was never reported.
    - **Which minutes:** any minute with work after the checkpoint is waived whole. The renter never pays for lost work, even half a minute of it.
    - **One waiver per attempt** (`metering.waivers`), recorded under the session's row lock:
      - waived minutes that weren't settled are never charged; settlement skips them even before they're marked;
      - minutes already settled are refunded in one reversing transaction, `txn_id` = name-based UUID of `session-waiver:{session}:{attempt}`;
      - the refund credits the renter and debits each host's payable and the platform's revenue in the proportions of the settlement part that charged them: host = floor(refund × part share / part amount), platform = the rest. Refunding a whole part gives back exactly what each side was credited.
    - Doing it again changes nothing. Usage that arrives after the waiver is waived on the next tick.

### What a host sees

22. **`GET /v1/hosts/{id}/earnings`**, for the host's owner only (anyone else gets a 404):
    - the host's `host_payable` balance;
    - its ledger entries, newest first, with a cursor: `session_earning` credits and `lost_minutes_refunded` debits.

    The billing module asks the hosts module who owns a host (`HostOwners`). **No payouts or escrow yet:** they come with the payment partner.

### The review queue

23. **Minutes that can't be charged go to `metering.review_minutes`,** with a reason:
    - `agent_only` or `lease_only`: one witness, at close;
    - `over_limit`: both witnesses but past the cap or the credit;
    - `late_report`: arrived after the session closed.

    They're kept for a person to look at; nothing charges them automatically. **Open question:** who works the queue, and on what schedule. Disputes are out of scope too.

## Alternatives considered

- **Bill whatever the agent reports.** It breaks the two-witness rule (ADR 0005): an agent could invent minutes, and a host could bill while partitioned away.
- **Coverage from the agent's lease clock, or allowing gaps up to the transit allowance.** The agent's clock isn't ours to trust. Allowing gaps would charge minutes the control plane can't prove were leased.
- **Settle every minute.** It would mean a ledger transaction per minute per session, and wallet-lock contention. Fifteen-minute windows, with early settlement when the hold runs low, keep postings few and the hold accurate.
- **Random `txn_id`s with a "settled" flag.** A crash between posting and flagging would charge twice. A name-based id makes the retry a no-op in `Ledger.post`.
- **A floating-point commission, or splitting the session total once.** Splitting each host's part, flooring the host share, keeps every paisa integral and the split auditable per host.
- **Prorating the partial last minute.** Seconds billing invites rounding disputes and a fractional-paise ledger. "A started minute is a whole minute" is simple to state and matches how the plan prices.
- **Waive from the moment of loss, or the last reported checkpoint.** The first charges for work the next attempt redoes. The second ignores a checkpoint finished but not yet reported. Storage's own marker time is the honest boundary, on a clock neither party controls.
- **A durable usage queue on the agent.** It would need a write per minute on a gaming machine's disk, for minutes that are never charged without the lease witness anyway. Reconsider if lost minutes show up in review.

## Consequences

- A minute is money only when two independent records agree, and the agent's clock and word never decide coverage.
- The hold always covers the next lease. Running out ends in a checkpoint and COMPLETED, never a negative wallet. The database, the settlement limit and the renewal rule each enforce it independently.
- Every hold change and every charge can be traced to its cause, and the nightly check and `reconcile-wallets` prove the caches match.
- The end-to-end test grows by a few minutes: four charged scenarios run side by side on four agents.
- Watch:
  - `metering.usage.reports` by outcome;
  - `sessions.leases.not_renewed`;
  - the size of the review queue;
  - `billing.reconciliation.mismatches`, which must stay 0.
- Open question: who reviews `review_minutes`, and what a reviewer may do with them.
