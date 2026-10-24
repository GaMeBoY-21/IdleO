# 0040. Money out

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Hosts' earnings are in the ledger (ADR 0017, ADR 0023), but nothing pays them. The plan says how it should go
(docs/plan/execution-plan.md, "How money flows", step 3): "Every week, host balances older than 7 days and not under
dispute are paid to the host's verified UPI ID or bank account." This round builds that through RazorpayX, with the
host's identity and UPI ID checked first, the right tax deducted at source, and money recovered when a renter's report
is decided against a session that was already paid out (docs/prompts/payouts-out.md). It also closes ADR 0037's open
item: a host with unpaid earnings couldn't be deleted.

There's no company, no current account and no CA yet, so everything runs in RazorpayX's test mode and no real money
moves. The rules that bind every part:

- Money moves only through billing's `Ledger.post`, one balanced transaction named by its cause (ADR 0014). The payouts
  module owns RazorpayX and the records; billing owns every posting, through its `HostPayouts` port.
- Database first, then side effects: the money is in flight in the ledger before RazorpayX is asked to pay.
- We use only RazorpayX's documented request and response fields and its documented sample payloads. The pages are
  listed at the end; every sample the tests use is a file copied from them (`control-plane/src/test/resources/razorpayx/`,
  with each one's page and every change in its README).
- Nothing the CA or the lawyer decides is settled here: those values are `legal:` settings marked LEGAL(confirm), listed
  at the end with the question each needs answered.

## Decision

### When earnings become payable

- **Pending, then available.** A session's net earnings on a machine are pending until `legal.payouts.dispute-window`
  (7 days, LEGAL(confirm)) after the session's last charge, which is its end, and while a report about it is open
  (`billing.payout_holds`, ADR 0028). After that they're available. Nothing is posted when the window ends: the ledger's
  entries and the holds say what's pending, so the figure can't drift (`HostPayouts.position`).
- **A report pauses only its own session.** An abuse review of the renter doesn't touch the host's earnings: the host
  did nothing wrong (ADR 0038).
- **Weekly.** Every Monday at 10:00 India time (`payouts.weekly-cron`), everyone with a UPI ID payouts may go to and at
  least `payouts.minimum-paise` (₹100) available is paid, one payout for all their machines. One instance claims the
  day's run (`payouts.runs`, unique a payday).
- **No "pay me now".** Hosts wait for the weekly run. An admin can pay one person at once (Retry, after a failure), and a
  deletion's final payout goes at once; nothing else is outside the schedule.
- **Fees.** RazorpayX charges per payout. Who pays is `payouts.fees.paid-by`: `idleo` (the default) or `host`, who then
  has `payouts.fees.host-fee-paise` taken from each payout into `platform_revenue`. This is Nikki's business decision,
  not the law's. RazorpayX's own fees aren't in the ledger yet, as Razorpay's aren't for money in (ADR 0039).

### Days from earning to money

With the defaults, earnings reach the host 7 days after the session (the window) plus the wait for the next Monday: 7 to
14 days, about 10.5 on average, and then minutes for a UPI payout to be processed. `payouts.days-to-paid` measures it on
every processed payout, from the oldest earning it carried.

### Verifying a host before the first payout

- **PAN.** The host gives their PAN and their name exactly as on it (Hosting, Payout details).
  - The format is checked here: five letters, four digits, a letter, and the fourth letter one of P (individual), H
    (family), C, F, A, T, B, L, J (a business or body). G, a government's, is refused.
  - Then `PanVerifier`, one small interface: verified, a name that doesn't match (an admin looks), no such PAN
    (rejected), or not checked (an admin confirms it by hand). No provider is integrated yet: production uses
    `ManualPanVerifier`, so every PAN waits for an admin, who confirms it in the console with a reason, audited. The
    tests and the local stack use `FakePanVerifier` (`payouts.pan.verifier: fake`, refused in production): digits 0000
    are no PAN, digits 9999 another person's.
  - Three providers to choose from, each with a documented PAN verification API:
    - Setu: https://docs.setu.co/data/pan (verify a PAN against NSDL with consent; returns the name and category).
    - Cashfree Verification Suite: https://www.cashfree.com/docs/api-reference/vrs/v2/pan/verify-pan-sync (returns the
      registered name and whether it's an individual's or a business's).
    - Decentro: https://decentro.tech/resources/pan-verification-api (product page; its API reference is behind a sign-up,
      to be read before choosing).
- **The UPI ID.** RazorpayX's Fund Account Validation for a VPA, which makes the contact (named as on the PAN, our
  opaque user id as its reference) and the fund account in the same call, must say `active`. Its registered name is
  shown to the host ("Check it") so they recognise it, and its name match score (against the PAN's name) must be at
  least `payouts.name-match-threshold` (80). Below that, the host's confirmation sends it to an admin, who approves or
  rejects it with a reason, audited. An answer that takes a while comes by the `fund_account.validation.*` webhooks or a
  sweep. Each check is a penny drop live, so a person may make `payouts.checks-per-day` (5) a day.
- **Storage.** The PAN is sealed with AES-256-GCM under `payouts.pan.key` (`PAYOUTS_PAN_KEY`, 32 bytes, the same pattern
  as the admin console's TOTP key: a fresh nonce, the person's id as associated data), and shown only by its last four
  characters. Only two things open it: the quarter's tax export and an admin who reveals it with a reason, both audited.
- **Changing the UPI ID** needs a sign-in in the last 10 minutes (ADR 0037's `auth_time` rule), checks the new one, and
  holds payouts for `payouts.change-hold` (48 hours) with an email to the host: "Your payout UPI ID was changed. Not you?
  Contact us." The same UPI ID checked again (after a reversal) isn't a change and isn't held.
- **Renters who buy a lot.** A renter whose bought credit would pass `legal.payouts.renter-pan-above-paise` (₹2,00,000,
  LEGAL(confirm)) in a financial year gives a verified PAN first: the same form and check; a top-up past it is refused
  `pan-required` until then. Billing asks through its `VerifiedPans` port, which payouts implements.

### Paying out

1. **The run, once per person a week**, in one database transaction (`PayoutService.create`, under the person's lock):
   1. billing recovers anything the person owes (a clawback, below) from what's available, then moves the rest out of
      each machine's `host_payable` into `payouts_in_flight` (`HostPayouts.start`), the tax deducted to `tds_payable`;
   2. our payout row is written `created`, with the tax, its section and rate, and the financial year.
2. **Then RazorpayX is asked** (`POST /v1/payouts`): `mode: UPI`, `purpose: payout`, `narration: IdleO earnings`,
   `queue_if_low_balance: true`, `reference_id` and `X-Payout-Idempotency` both our payout's id. Sending it again (an
   answer lost) pays nothing twice; out of reach, it's sent again every minute with the same key; refused, the money goes
   back at once.
3. **Outcomes**, from webhooks (each payout fetched from RazorpayX's API, which decides), the answer to the call, a status
   poll after 30 minutes without news, and the nightly reconciliation:
   - `processed`: the money left. `payouts_in_flight` to `payouts_paid`, and the host is told.
   - `failed`, `rejected`, `cancelled`: everything back to the machines' earnings, with the tax and any fee. The next run
     tries again; the host is told why, in RazorpayX's words for the reason.
   - `reversed` (the bank sent it back, even after `processed`): everything back, from paid or from in flight; the UPI
     ID is marked suspect, so nothing more goes to it until the host checks a UPI ID again; the host is told.
   - Statuses only move forward; one that came back stays back; a `processed` one can still be `reversed`
     (https://razorpay.com/docs/x/payouts/states-life-cycle/).
4. **Webhooks** (`POST /v1/payouts/razorpayx/webhook`): `X-Razorpay-Signature` on the raw body under
   `RAZORPAYX_WEBHOOK_SECRET`, checked before the body is read; each event handled once by `x-razorpay-event-id`; none
   of the payload kept but RazorpayX's ids.
5. **Reconciliation:** every night at 03:45 India time, and when an admin asks, a day's payouts are listed from RazorpayX
   and compared with ours: `missing_ours`, `missing_theirs`, `amount`, `status`. A payout ours was merely behind on is
   moved on by the path a webhook takes, not listed. Items are kept once by kind and subject (`payouts.mismatches`),
   fixed by a later run that finds them right, or accepted by an admin with a reason. `payouts.reconciliation.open` must
   be 0; the PayoutMismatch alert fires on any.
6. **Low balance:** the day before payday (Sunday 10:00), RazorpayX's available balance is checked against what the run
   would pay. Short, `payouts.balance.short` is 1 and PayoutBalanceLow fires while there's time to fund the account.

### Tax deducted at source

- **What the rules say**, for the CA to confirm (LEGAL(confirm) on every value, all in `legal.tds`):
  - Section 194-O of the Income-tax Act, 1961 has an e-commerce operator deduct tax on the gross amount of sales of goods
    or services through its platform: 0.1% from 1 October 2024 (cut from 1% by the Finance (No. 2) Act, 2024).
  - 5% when the participant gives no PAN (section 206AA).
  - Nothing for a resident individual (or family) whose gross amount in the year is up to ₹5,00,000 and who has given a
    PAN.
  - The Income-tax Act, 2025 renumbers the sections from 1 April 2026. The section's name is a setting
    (`legal.tds.section`, `no-pan-section`), recorded on each payout, never written in code.
- **How it's built** (`TaxDeduction`): the basis is the gross the person was paid in the financial year (April to March,
  India's time; `FinancialYear`, shared with invoices), counting every payout that didn't come back. An individual with
  a PAN: nothing while that's at or under the threshold; once it's over, the rate on the whole year's gross less what was
  deducted before, so the year's deduction always comes to the rate on the year's gross (catching up the part under the
  threshold). A business: the same, from the first rupee. No PAN: the no-PAN rate on the payout. Rounded to the nearest
  paisa, half up. Deducted at payout time and recorded on each payout.
- **Hosts without a PAN** can't be paid: verification is required. `legal.tds.pay-without-pan` (off) would pay them at
  the no-PAN rate if the CA says to.
- **Exports:** the quarter's deductions as CSV for the CA (admin console, Tax: each payout with the host's whole PAN and
  name, section, rate, gross, tax, net and dates; audited), and a yearly statement per host under Hosting, Earnings (PDF
  and CSV: gross paid out, tax deducted, net paid, each payout).
- **Production** refuses live RazorpayX keys while `legal.tds.confirmed` is false: the CA confirms first.
- **GST on hosts:** most hosts are individuals not registered for GST. Whether the platform owes GST for services
  supplied through it by unregistered people (reverse charge, or section 9(5) of the CGST Act), and whether section 52's
  TCS applies, is the CA's question. No code.

### Clawbacks

- **When:** a renter's report refunded after the session's earnings were paid out (`billing.payout_sessions` records
  what each payout paid each machine for; a payout that came back voids its rows). The refund debits the machine's
  `host_payable` as always; that part is a clawback (`billing.clawbacks`).
- **First from the balance:** the owner's pending and available earnings cover it, moved between their machines so none
  is below zero.
- **The rest is owed:** a `host_receivable` of the owner's, recovered from their next available earnings before any
  payout (`earnings_recovered`). It's never sent to RazorpayX as a debit: we can't take money from a host's UPI ID.
- **The host** sees the line ("Recovered: refund for session 0193ab4c on 12 Nov") in Earnings and is emailed, never
  told who the renter was.
- **Waiving:** an admin may waive what's owed, with a reason, audited; IdleO absorbs it into `chargeback_losses`.
- **Chargebacks** don't claw back hosts' earnings: a chargeback is on a renter's payment, not a session, and the hosts
  did the work. The loss stays IdleO's (ADR 0039). Open question: whether a chargeback whose sessions were also wrong
  should claw back through a report.

### Deleting a host's account

- **Earnings never stop the request.** The deletion screen says what's paid when (`PersonalData.waits`, new): what's
  available is paid when the waiting period ends, and what's pending once its window ends, with the date.
- **At the erasure** (`PayoutsPersonalData.beforeErasure`), a final payout goes at once to the verified UPI ID, outside
  the weekly run, above RazorpayX's ₹1 minimum; the erasure waits while it's on its way and while anything is pending,
  then goes ahead.
- **No UPI ID payouts may go to:** a blocker. The host adds one, or gives the earnings up in writing
  (`POST /v1/payouts/forfeit`, a fresh sign-in, their own words kept, audited; the money goes to `earnings_forfeited`).
- **What's kept:** the PAN and the payouts are money records the tax filings need, kept by opaque id for
  `legal.retention.money-records`; the UPI IDs and the names banks gave them are cleared (docs/reference/personal-data-map.md).

### The ledger

| What | Debit | Credit |
| --- | --- | --- |
| A session's earning | the renter's credit | `host_payable` (the machine) |
| The dispute window ending | nothing: pending and available are read from the entries and the holds | |
| A payout started | `host_payable` (each machine, what's available) | `payouts_in_flight` (net), `tds_payable` (tax), `platform_revenue` (a host-paid fee) |
| … processed | `payouts_in_flight` | `payouts_paid` |
| … failed, rejected or cancelled | `payouts_in_flight`, `tds_payable`, `platform_revenue` | `host_payable` (the machines it came from) |
| … reversed | `payouts_paid` (or `payouts_in_flight`), `tds_payable`, `platform_revenue` | `host_payable` |
| A refund of paid-out earnings, covered | `host_payable` (the owner's other machines) | `host_payable` (the refunded machine) |
| … owed | `host_receivable` | `host_payable` |
| Recovered before a payout | `host_payable` | `host_receivable` |
| A clawback waived | `chargeback_losses` | `host_receivable` |
| Earnings given up in writing | `host_payable` | `earnings_forfeited` |

`verify-ledger.sh` adds three checks: no one's in-flight payouts are below zero; each person's `payouts_paid` equals the
sum of their processed payouts; a receivable is only ever recovered or waived, never paid out (no `host_receivable`
entry in a payout's transaction, and none below zero).

### Testing without real money

- **The fake:** a stateful fake RazorpayX (`FakeRazorpayX`), a WireMock extension on the control plane's **test**
  classpath only, built from RazorpayX's documented samples: checks (active, invalid, a low name match, a slow one),
  payouts through every status including reversed, the balance, and signed webhooks. It refuses a payout without
  `X-Payout-Idempotency` and answers a repeat with the same payout.
- **The local stack** runs it as `FakeRazorpayXServer`, which moves each payout on by itself and sends the webhooks: a
  UPI ID starting `payoutfails@` fails, `reversed@` is processed then reversed, anything else processed.
- **The real test-mode check** is in docs/runbooks/payouts.md, with Nikki's own RazorpayX test keys.

### Production

`ProductionSettingsGuard` refuses: RazorpayX test keys, an API other than Razorpay's, keys without a webhook secret or
account number, live keys while `legal.tds.confirmed` is false, no PAN key (or the tests' committed one), and the fake
PAN check; and RazorpayX's live keys anywhere but production. Production may run without RazorpayX keys: payouts are
then off and UPI checks answer `payouts-unavailable`.

## Alternatives considered

- **Payouts from billing itself:** one module would hold the ledger, Razorpay and RazorpayX. The payouts module keeps
  RazorpayX, verification and tax; billing keeps every posting behind `HostPayouts`, so the ledger's one writer stays.
- **A posting when each window ends:** a sweep moving pending to available would be a second source of truth for the
  window and a job to watch. Reading it from the entries and the holds can't drift.
- **"Pay me now":** more payouts, more fees, and a hold that a stolen account could race. Weekly is the plan's.
- **Bank accounts as well as UPI:** UPI covers the hosts we have; a bank account adds IFSC checks and NEFT timing. Later.
- **Debiting a host's UPI ID for a clawback:** RazorpayX can't, and it would be the wrong thing to ask of a host.
- **Separate RazorpayX contact and fund account calls:** the documented VPA validation makes both in one call and
  returns their ids; two more calls would add nothing.
- **Integrating a PAN provider now:** the choice is Nikki's (cost, terms); an admin confirming by hand is enough for the
  pilot's numbers.

## Consequences

- **Fixed rules:** money leaves only after it's in flight in the ledger, once, under its own idempotency key, and every
  outcome is a named posting the restore drill's `verify-ledger.sh` checks.
- **Daily and weekly jobs** with alerts: the run (Mondays), the balance check (Sundays), the reconciliation (nightly),
  the poll and the resend (every few minutes).
- **Before going live:** a RazorpayX business account with the company's KYC and its current account; live keys, the
  webhook and its secret, IP allowlisting (RazorpayX requires it); the CA's answers (`legal.tds.confirmed`); a PAN
  provider or an admin's time for each PAN (docs/runbooks/payouts.md, "Going live").
- **Watch:** RazorpayX's documented samples disagree with themselves (`results` and `validation_results`, a score as a
  string or a number, broken JSON); the client reads either, and the real test-mode check confirms which.

## The LEGAL(confirm) table

| Setting | Proposed | The question to ask |
| --- | --- | --- |
| `legal.payouts.dispute-window` | 7 days after a session's last charge | Our lawyer: is holding a host's earnings 7 days for renters' reports fair and enforceable under the host terms, and must the terms say so? |
| `legal.payouts.renter-pan-above-paise` | ₹2,00,000 bought in a financial year | Our CA and lawyer: at what amount must we collect a renter's PAN (Rule 114B of the Income-tax Rules for cash-like payments, KYC), and is ₹2,00,000 right? |
| `legal.tds.section` | 194-O | Our CA: does section 194-O apply to us as an e-commerce operator paying hosts for GPU time, and what is its section under the Income-tax Act, 2025 from 1 April 2026? |
| `legal.tds.basis-points` | 10 (0.1%) | Our CA: is 0.1% the current rate, and is the basis the host's share we pay (not what the renter paid)? |
| `legal.tds.no-pan-section`, `no-pan-basis-points` | 206AA, 500 (5%) | Our CA: is 5% right without a PAN, under which section in the 2025 Act, and should we ever pay a host without a verified PAN (`pay-without-pan`)? |
| `legal.tds.individual-threshold-paise` | ₹5,00,000 a year | Our CA: is the exemption ₹5,00,000 of gross in the year for a resident individual or HUF with a PAN, and once it's passed, is tax due on the whole year's gross (as built) or only on the part above? |
| `legal.tds.confirmed` | false | Our CA: once the above is answered, set it true; production refuses live payouts until then. |
| TDS timing and filing | deducted at payout, filed quarterly from the export | Our CA: is tax deducted when we credit `host_payable` or when we pay, which return (26Q or its successor) and which certificate do hosts get? |
| GST on hosts' services | none, no code | Our CA: as a marketplace, do we owe GST on services supplied through us by unregistered hosts (section 9(5) or reverse charge), and does section 52's TCS apply to these payouts? |
| `legal.retention.money-records` for PANs | 8 years, kept on erasure | Our lawyer: may we keep an erased host's PAN and payout records for the tax period under the DPDP Act, and must the privacy notice say so? |
| Who pays RazorpayX's fee | IdleO (`payouts.fees.paid-by`) | Nikki's business decision, not legal: whether hosts bear it, and if so how much. |

## RazorpayX's pages relied on

- [Get started with the API](https://razorpay.com/docs/api/x/), [test mode](https://razorpay.com/docs/x/dashboard/test-mode/)
- [Contacts](https://razorpay.com/docs/api/x/contacts/), [create a contact](https://razorpay.com/docs/api/x/contacts/create/) (the name's rules)
- [Fund accounts](https://razorpay.com/docs/api/x/fund-accounts/), [create a VPA fund account](https://razorpay.com/docs/api/x/fund-accounts/create/vpa/)
- [Validate a VPA](https://razorpay.com/docs/api/x/account-validation/vpa/), [composite VPA validation](https://razorpay.com/docs/api/x/composite-account-validation/vpa/), [fetch a validation](https://razorpay.com/docs/api/x/account-validation/fetch-transactions-with-id/), [validation webhooks](https://razorpay.com/docs/webhooks/account-validation/)
- [Create a payout to a VPA](https://razorpay.com/docs/api/x/payouts/create/vpa/), [idempotency](https://razorpay.com/docs/api/x/payout-idempotency/make-request/), [fetch a payout](https://razorpay.com/docs/api/x/payouts/fetch-with-id/), [fetch all payouts](https://razorpay.com/docs/api/x/payouts/fetch-all/)
- [Payout states and life cycle](https://razorpay.com/docs/x/payouts/states-life-cycle/), [payout status details](https://razorpay.com/docs/errors/x/payout-status-details/)
- [Payout webhooks](https://razorpay.com/docs/webhooks/payouts/), [validate and test webhooks](https://razorpay.com/docs/webhooks/validate-test/), [set up payout webhooks](https://razorpay.com/docs/webhooks/setup-edit-payouts/)
- [Fetch balance](https://razorpay.com/docs/api/x/account-validation/balance-fetch/)

## Tax sources

- Income-tax Act, 1961, section 194-O (TDS by e-commerce operators) and section 206AA (no PAN), as amended by the
  Finance (No. 2) Act, 2024 (rate 1% to 0.1% from 1 October 2024).
- Income-tax Act, 2025, in force from 1 April 2026, which renumbers them: the CA confirms the section.
- CBDT Circular No. 17 of 2020 (guidelines on section 194-O), for the CA's reading on basis and timing.
