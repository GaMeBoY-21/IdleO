# 0055. Money operations

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The ledger (ADR 0005, ADR 0014) records every paisa, and the database already keeps it honest: V6's deferred constraint trigger refuses, at commit, any transaction whose debits aren't its credits, and the ledger is append-only. What was missing is running the money as a company does:

- seeing every account's balance at a moment;
- checking the ledger's rules in production rather than only after a restore;
- correcting a mistake without an edit;
- closing a month so the CA's figures stay final;
- reading the headline figures without writing SQL.

`ops/backup/verify-ledger.sh` held the ledger's rules as SQL of its own, and the nightly `WalletReconciliation` restated four of them.

## Decision

### The chart of accounts

Every account is one value of `Account`, with:

- its type: asset, liability, income or expense;
- its normal side;
- whether a renter or host owns it;
- what it holds, in plain words.

The database's check constraint names the same list, and a test holds them equal. Three accounts are new: `gateway_fees` (expense), `gst_input_credit` (asset) and `write_offs` (expense). Two are contra accounts, and their descriptions say so:

- `gst_on_unused_credit` is a liability on the debit side;
- `payouts_paid` is an asset on the credit side.

### The explorer

`ledger.read` holds the console's Ledger screen:

- each account's balance at any moment, for the platform or one owner;
- the trial balance at any moment, with a red banner if the columns differ;
- every entry through the list engine, with saved views and CSV, by account, owner, cause and time;
- one transaction with every leg and the record it came from.

### The ledger's checks, one list

The checks are database functions, defined once in V78:

- `billing.ledger_check_findings()`: each finding, what it's about and the two figures;
- `billing.ledger_checks()`: each check's count.

The nightly run, an admin's run from the console (`reconciliation.run`) and `verify-ledger.sh` all read them, in a read-only transaction. That's the database's guard against a check writing, in place of a separate login: a cluster's migration user can't create roles. Every run keeps all 17 counts. `billing.ledger.checks.failing` and `billing.ledger.unbalanced` page.

### Manual adjustments

An adjustment is a journal entry of two or more legs that balance. Each leg is on an account an adjustment may touch, with an owner where the account needs one. It carries a reason and a reference.

- **Accounts an adjustment can't touch:** a renter's credit and the GST paid ahead on it, refunds pending, frozen credit, and payouts. Each is moved only by its own flow, which the checks hold to its records.
- **Templates are fixed shapes:**
  - a fee correction: gateway fees from clearing;
  - a write-off: write-offs from clearing, at most ₹100;
  - a goodwill credit, which is a grant (`CreditGrants`, with its caps and lot), recorded as an adjustment too.
- **Approval:** every adjustment is another admin's approval (`money.adjust`, ADR 0051), with its legs as the diff. It posts only when approved, as one ledger transaction named by the approval.
- **Reversal:** a reversal is an adjustment of its own, the legs the other way round, naming what it reverses. A goodwill credit's reversal is the grant's.

### Closing a month

Months are India's.

- **The checklist:** each module declares its items as `MonthCloseCheck` beans. Billing's are the ledger's checks passing in a run after the month ended, and no open payment reconciliation item from the month's days.
- **Closing:** the Owner closes a month that's over, once its checklist holds (`periods.close`: step-up and an approval). From then a trigger on `billing.ledger_entries` refuses any entry dated in it, whatever the application does.
- **Corrections:** a correction goes into an open month, as an adjustment that names the closed one (`corrects_month`).
- **Reopening:** it takes the Owner, an approval and a reason. It's audited and pages (`PeriodReopened`).

### Settlement reconciliation

Every night at 04:30 India time, and when an admin asks (`reconciliation.run`), a day's settlements are fetched from Razorpay with every item they carried (the combined settlement reconciliation report), kept by Razorpay's ids, so a second fetch adds nothing.

- **Matching each item** (`recon.read`):
  - a payment with the top-up it paid, the same amount, and a fee and its GST no more than we expect (`billing.settlements.fee-basis-points`, 2%, and 18% GST on the fee);
  - a refund with the refund part Razorpay processed;
  - a deduction with the chargeback lost on that payment.
- **What doesn't match** is `missing_ours`, `amount` or `unexpected_fee`. A payment we credited that no settlement carried 3 days later is `missing_theirs`, until one does (`settled_later`).
- **A settlement** is matched, partly or unmatched; unmatched also when its items don't add up to what it settled.
- **Posting:** a processed settlement posts once: what it moved to the bank (`bank`, a new asset account), Razorpay's fees (`gateway_fees`) and their GST (`gst_input_credit`), out of `gateway_clearing`.
- **Resolving** (`recon.resolve`): an item is resolved by an approved adjustment whose reference names it, or as a known difference with a reason. One open for more than 3 days alerts (SettlementItemsStale), and every open one is an inbox item.
- **RazorpayX:** its payouts were already reconciled against ours every night (ADR 0040), and that stays as it is.

### Payout batches

The weekly run is one batch:

- **The preview:** each host's quote (`PayoutService.quote`), with the same checks and figures as the payout itself: the gross (what's available less what's owed, which is recovered first), what's held, the tax, the fee, the net and the masked UPI ID. Anyone left out is listed with why.
- **Release** is one approved action (`payouts.release`, step-up). It takes every quote again first and pays nobody if any changed, so what's paid is what the approver saw. Then it pays each host as previewed, once. A batch is released once; the same approval run again finishes what it started.
- **Tracking:** each item shows its payout's status, and the items' CSV is the batch report for the CA.

### Tax exports and the CA's monthly pack

Each file for the CA is a `TaxExport` bean of the module that owns its data. Billing holds GST and the ledger; payouts holds TDS.

- **GST:**
  - the invoice register;
  - credit notes;
  - GSTR-1's data: B2B invoice by invoice with the buyer's GSTIN, B2C summed by place of supply and rate, and credit notes to registered buyers. It comes as JSON shaped for the GST portal's offline tool (money in rupees there, written exactly from paise) and as CSV;
  - the month's summary, the documents' GST against the ledger's tax accounts.
- **TDS (194-O, or 206AA without a PAN):**
  - the quarter's deductions payout by payout;
  - 26Q's deductee records;
  - Form 16A's data per host.

  The rates come from `legal.tds`.
- **The ledger:** the trial balance at the month's end, the journal and the settlement reconciliation.

Every file says what it adds up to, and a test ties each figure to the entries behind it.

Asking for a file or for the month's pack is `tax.export`, with a factor and another admin's approval: the files hold whole PANs and GSTINs. The asker downloads it once from Approvals.

The pack is one zip of every file the pack takes, with a manifest of each file's SHA-256, size and totals. It's made when it's downloaded; the manifest's hash goes into the audit log and onto `billing.ca_packs`, but the pack itself, with its PANs, isn't kept. The month-close checklist waits for it.

The quarterly TDS export the console had (`tds.export`) is now one of these files, written once.

Every format waits on the CA (CA(confirm)), and so do:

- the SAC code;
- the place of supply;
- the TDS rates;
- whether IdleO, as an e-commerce operator, must collect TCS under section 52 of the CGST Act on hosts' supplies. Until the CA says so, TCS isn't built: no account and no export, and production still refuses `tcs: true` (ADR 0039).

### Money reports

`money.read` holds:

- **The headline figures** by day, week or month: GMV, the take, refunds, chargebacks, what promotions cost, Razorpay's fees and the net.
- **The liabilities:** renters' bought and free credit, what hosts are owed aged by when they earned it (0–7, 8–14, 15–30 and over 30 days), and free credit's breakage by month.

Each figure is defined once (`MoneyReports.HEADLINES`) as the entries it sums. The console links it to those entries, and a test ties every figure to them.

## Alternatives considered

- **A second database login for the checks.** In a cluster, roles come from the database's first start (ADR 0045), so a new one needs a new cluster or a manual step. A read-only transaction gives the same guard from the database.
- **Keeping verify-ledger.sh's SQL and WalletReconciliation's apart.** Two statements of the same rule drift. One already had: the script checked lots below zero, the nightly run didn't.
- **Adjustments on any account.** One on a lot or a payout would break the checks that hold those flows to their records.
- **Closing a month in the application only.** A bug, a script or a manual statement would still write into it; only the database's own refusal makes a closed month final.

## Consequences

- A correction is always a new transaction, so the ledger's history shows what was wrong and who put it right.
- Closing a month is final for postings. A webhook that arrives late for a closed month posts into the open one, because its entry is dated when it's posted.
- The checklist grows with each module's items: settlements reconciled and the month's payout batches settled are declared by billing and payouts; the CA's pack follows.
- Hosts are paid only once a batch is released by approval, no longer by Monday's run on its own. A week nobody releases is a week nobody is paid; the month-close checklist and the batch's place in Payouts make it hard to miss.
- The expected fee rate is a setting. A method Razorpay charges more for shows as an unexpected fee until the rate or the method is put right.
- Open question: whether the CA wants corrections to a closed month shown against that month in the pack, or only in the month they're posted. They're posted in the open month and name the closed one, so either can be drawn.
