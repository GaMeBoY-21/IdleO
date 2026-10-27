# 0028. Ratings and reports: stars for a host's average, a report an admin answers once, refunds from the commission first, and a hold on the host's payout

- **Status:** Accepted
- **Date:** 2026-10-13

Sources:
- ADRs 0005 and 0014 (the ledger and its accounts), 0017 (what a minute costs and who takes a loss), 0023 (hosting and host earnings), 0026 (notifications), 0027 (the admin console)

## Context

A renter whose job failed or ran badly had nowhere to say so but an email to the product owner, and the only money that ever went back to a renter was the lost-host refund (ADR 0017). A host had no way to know how renters found its machine. The admin console (ADR 0027) shipped without its Disputes screen, and the trust module held nothing.

The gate: on the local stack, a renter rates a completed session and reports a problem with a failed one; an admin sees the report in the console and refunds part of it; the renter's credit and the ledger agree; the host sees its average rating and never who rated it.

Constraints:
- **Modules talk through public APIs or events** and never read another module's tables. Trust must know who ran a session and when it ended without reading sessions.
- **Money moves only through billing's ledger** (ADR 0014), in one balanced transaction, with `txn_id` derived from its cause.
- **A renter and a host never see each other**. A host sees a rating's average, never its author or its words.
- **The plan is silent** on whose money a refund is, on how long ratings count, and on what happens to a host's earnings while a report is open. This ADR decides.

## Decision

### Ratings

- **Who and when.** Only a session's renter rates it, and only once it has ended having run at least a minute: its end at least 60 s after its first RUNNING, on the control plane's clock. A rating is given within 7 days of the session ending. Each session has one rating, which its renter can change in place within 7 days of giving it.
- **What.** 1 to 5 stars, and an optional comment of up to 500 characters. A rating is for the host of the session's last attempt that ran.
- **The host's average.** The mean of the stars of the ratings of sessions that ended in the last 90 days, to one decimal place, with how many. It is shown only once there are 5 of them, so no one rating can be picked out. It appears on the owner's host (`rating`) and nowhere else. The comment is never shown to a host; only admins read it (`GET /admin/v1/ratings`, and beside a report). Host scores will use ratings later; nothing uses them yet.
- **No new dependency.** The hosts module declares `HostRatings`; trust implements it, as hosts declares `HostPresence` and the agent gateway implements it (ADR 0013).

### Trust keeps its own record of an ended session

Sessions' `SessionEnded` and `SessionNeverRan` events now carry the renter, the host of the last attempt that ran, the final state and the start and end times. Trust's durable listeners write them to `trust.ended_sessions`, once each (the insert ignores a repeat). A renter rates and reports only sessions found there; another person's session, or one that hasn't ended, is `not-found`, never a 403. A session cancelled before it ran leaves no record, so it has nothing to rate or report.

### Reports

- **Opening.** A renter reports their own ended session within 7 days of it ending, with one of six reasons (`didnt_start`, `failed`, `slow`, `wrong_output`, `charged_wrong`, `other`) and details of up to 2,000 characters. A session has at most one open report (a unique index); after one is resolved, another may be sent. The request takes an Idempotency-Key.
- **States.** `open`, `refunded` (resolved with a refund) and `not_refunded`. The renter reads our answer and the refund; never which admin gave it.
- **Resolving.** An admin resolves a report once, in one transaction with its audit row (`dispute.refund` or `dispute.no_refund`, ADR 0027): either a refund of an amount, or no refund; either way with a reason that the renter reads (the admin's reason, 5 to 500 characters). Resolving the same way again changes nothing; resolving another way is refused (`action-refused`). A trigger refuses any other change to a report.
- **What the admin sees.** The report, the session's end, the session's timeline (the sessions record's own), each minute as both witnesses saw it with its settlements and waivers (`GET /admin/v1/sessions/{id}/usage`, read-only), what the session cost and who kept what, now, from the ledger, and its rating with the comment.

### Refunds

- **At most what the renter paid.** From the session's own ledger entries (`session` settlements, `session_refund` lost-time refunds and earlier `dispute_refund`s), net: the renter's wallet debits less its credits. A refund can't take a session below zero, and a session reported twice can be refunded in parts.
- **The platform's commission first, then the hosts.** One balanced transaction credits the renter's wallet and debits `platform_revenue` and each host's `host_payable`: the platform gives back what it kept of the session, up to the refund, and the rest comes from the hosts in proportion to what each kept, rounded down, with the paise left over going to the hosts whose shares lost most to rounding. No account is taken below what it kept for the session, so the nightly check and `reconcile-wallets` stay at zero mismatches. The split is `SessionRefunds.split`, a pure function with its own tests.
- **Exactly once.** `txn_id` is a name-based UUID of the report (`dispute-refund:{id}`), so a retry posts nothing, and the report's row keeps the transaction and the split (the commission's part and the hosts').
- **Honest host earnings.** A host's refunded part is its own ledger entry (`refunded_to_renter`, labelled "Refunded to renter") beside the earning, so earnings from the ledger always equal the owner's screen (ADR 0023). A renter's history shows it as `dispute_refund`, "Refund after your report".
- **A session's `chargedPaise` is net of these refunds;** its spending cap still counts what was charged before them, so a refund never lets a session be charged more.
- **Billing owns the money.** The trust module calls `SessionRefunds` (billing's public API) and never writes a ledger entry. The ledger-writer test still holds: nothing but `Ledger` writes `billing.ledger_entries` and `billing.wallets`.

### The hold on a host's payout

While a report is open, the session's host earnings are marked `payoutHeld` (a row in `billing.payout_holds`, placed when the report opens and ended when it is resolved, in the same transactions). The owner's earnings say "Held from payout while a report is open". Payouts don't exist yet; when they do, they skip what's held. A report about a session that never ran has no host and holds nothing.

## Alternatives considered

- **Trust reading sessions' tables, or a sessions port trust calls for each rating.** A tables read breaks the module rule. A port works but adds a call and a failure path per rating; events carry what trust needs once, and trust's record stays right if sessions' internals change.
- **Refunding from the host first, or only from the host.** The host did nothing wrong when a report is about the platform, and a host that gave back money it had been told it earned would stop trusting its earnings screen. The commission is ours to spend; the host's share is spent only when the commission is gone.
- **Refunding without a ledger cap, trusting the admin.** A typo (₹5,000 for ₹5.00) would debit the host and the platform into negative balances, which the database refuses; the cap makes the refusal a sentence, and the nightly check can stay at zero.
- **Showing hosts every rating, or the average from the first rating.** One renter's opinion would identify them. A threshold of 5 within 90 days is a floor on anonymity and keeps a bad first week from following a host forever.
- **Automatic refunds** (failed sessions refunded without a report). Out of scope: the lost-host refund already covers what the platform can prove (ADR 0017); the rest is a judgement for a person.
- **Email about reports.** The notifications module needs events from trust, listeners, words and the kinds in `NotificationKind`: not a one-file change, so it isn't built. The renter sees the answer on the session's screen and under Account, Reports.
- **A separate Disputes module.** The technical design's trust module already owns ratings, disputes and bans.

## Consequences

- A renter can say how a session went and be heard; an admin can make it right with money, with one audit row for each decision.
- A host's average is a figure that can't be traced to a person, and its earnings never include money it was made to give back without saying so.
- `SessionEnded` and `SessionNeverRan` grew fields; their only listeners (metering, notifications, trust) were updated. A session that ended before this change has no trust record: the app stops asking for its rating 2 minutes after its end, says it can't be rated, and a report on it is `not-found`. This matters only for sessions before this release; no real data exists yet.
- Reports are answered by hand. **Open question:** the 2-day reply is a promise to the renter with nothing counting it; a dashboard figure for open reports and their age belongs with the eleven metrics.
- **Open question:** a resolved report can't be reopened, and a refund can't be reversed. A mistake is a credit grant with a reason.
- **Open question:** what the payout hold means for money already paid out, once payouts exist.
- **Open question:** whether ratings should count lost-host sessions differently.
- **Watch:** the share of sessions with a report; refunds as a share of revenue, and how much came from hosts' shares; open reports older than 2 days; `billing.reconciliation.mismatches`, which must stay 0.

## Addendum (2026-10-14): a daily rating, and one report per session

- **A host's shown rating is worked out once a day** (`trust.ratings.refresh-cron`, 03:15 India time), into `trust.host_ratings`, never on each rating. With the average and the count changing right after one session, a host could have worked out that renter's stars. The count is shown rounded down to a multiple of 5 ("4.6 from 20+ ratings"; the API's field is `countAtLeast`), and a host still appears only from 5 ratings.
- **A session is reported once, ever.** Allowing a new report after each resolution let a renter place a new payout hold every time. The database enforces it (a unique index on the session); a second report while the first is open is still `report-already-open`, and one after it was resolved is the new `report-already-made`. The web app already offered no second report once one existed.
