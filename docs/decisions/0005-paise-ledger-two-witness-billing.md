# 0005. Integer paise, double-entry ledger, two-witness billing

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Neither the agent nor the control plane alone should be able to inflate a bill. A minute must be billed exactly once.

## Decision

- **Integer paise:** money is always stored as integer paise, never floating point. Hosts set hourly prices in steps of ₹0.60, so every minute costs a whole number of paise. Commission uses integer arithmetic, and any rounding remainder goes to the platform.
- **Double-entry ledger:** every paisa moves through a double-entry ledger, using the accounts renter\_wallet, host\_payable, platform\_revenue and gateway\_clearing.
- **Two witnesses:** a minute is billed only if the agent reported it and the control plane saw the lease alive. Each minute, the agent's UsageReport sets agent\_reported and the control plane sets lease\_alive; minute\_index counts from the control plane's start time, and agent clocks are never trusted.
- **Cycle:** reserve 30 minutes of cost at booking; accrue minutes with both flags every minute; settle every 15 minutes and at session end in one ledger transaction; top the reservation back up to 30 minutes after each settlement; warn at 10 minutes left and stop with a checkpoint first at zero; release the unused reservation at close; pay hosts weekly after a 7-day escrow.
- **Retry-safe settlement:** the minutes it bills are marked in the same transaction, and the ledger's unique key rejects a second posting of the same window; txn\_id is derived from session id and window.

## Alternatives considered

- **Floating-point money:** rejected.
- **Billing on one witness:** rejected, because neither side alone should be able to inflate a bill.
- **A ledger transaction every minute:** lost to 15-minute settlement, which keeps the ledger near 300 rows per session-day instead of over 4,000.

## Consequences

- Minutes only one witness saw go to a review queue and are never billed automatically.
- A top-up moves money from gateway\_clearing into renter\_wallet only after the gateway's signed webhook is verified.
- A nightly job checks that the ledger matches every wallet cache and the gateway's settlement report, and alerts on any difference.
- Each payout carries its own idempotency key.

Open question: the plan does not say who works the one-witness review queue, or how its minutes are resolved.
The worked example and the settlement code use a 20% commission, and the plan says to test 15–25% during the pilot, so the rate must be configurable.
The ledger's unique key is `UNIQUE NULLS NOT DISTINCT (txn_id, account, owner_id)`, so retries cannot double-post.
