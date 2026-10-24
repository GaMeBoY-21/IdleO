# 0039. Money in

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Until now the only credit was free credit an operator granted (ADR 0014). Renters must be able to buy credit, get an
invoice for it, have unused bought credit refunded, report a session that went wrong, and be protected, as we are, when a
bank disputes a payment (docs/prompts/payments-in.md). There is no company and no live payment account yet, so all of it
runs in Razorpay's test mode and no real money moves. The rules that bind every part:

- Money is integer paise and moves only through `Ledger.post`, one balanced transaction named by its cause (ADR 0014).
- Card and UPI details never reach our server.
- We use only the request and response shapes Razorpay documents, and the documented sample payloads, never an invented
  field. The pages relied on are listed at the end, and every sample the tests use is a file copied from them
  (`control-plane/src/test/resources/razorpay/`, with each one's page in its README).
- Nothing the law or the CA decides is settled here: those values are `legal:` settings marked LEGAL(confirm), listed at
  the end with the question each needs answered.

## Decision

### Credit is two kinds, kept in lots

- **What credit is:** IdleO credit is closed-loop: it can be spent only on IdleO.
- **Free credit** (grants, promotions, and give-backs) is `renter_wallet`.
- **Bought credit** is `renter_purchased`.
- **Lots:** every credit is a lot (`billing.credit_lots`) opened by its source: a grant, a top-up or a give-back. Every
  entry on a renter's account names its lot.
- **Spending order** (`Ledger.SPENDING_ORDER`): free credit first, the soonest to expire first and the never-expiring
  last, then bought credit, the oldest first. Spending free credit first means bought credit stays refundable for as long
  as possible.
- **Expiry:** free credit expires `legal.credit.free-expires-after` (90 days) after it's given (`FreeCreditExpiry`, daily,
  under an advisory lock), into `free_credit_expired`. Bought credit never expires.
- **Give-backs never expire.** A report's give-back (below) is a free lot that never expires, because the renter was
  wronged.
- **The move into lots:** migration V49 moved every balance into free lots expiring 90 days after the migration.
- **Free credit is never paid out:** a refund draws only on bought lots.

### Getting paid, with proof of capture

1. **Ordering:** `POST /v1/wallet/topups` (an Idempotency-Key, `@RequiresAcceptedTerms`) checks the limits and the billing
   details, then creates a Razorpay order ([Orders API](https://razorpay.com/docs/api/orders/create/)). The order's
   receipt is ours and unique, at most 40 characters; then our top-up row is written as pending.
2. **Paying:** the browser opens Razorpay's
   [Standard Checkout](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/) with the order.
   Card and UPI details stay in Checkout's window.
3. **The credit:** it is posted only once the server has fetched the payment itself
   ([fetch a payment](https://razorpay.com/docs/api/payments/fetch-with-id/)) and found it `captured` for this order, this
   amount, in INR. That proof can come from two places:
   - Checkout's handler, with its signature: HMAC-SHA256 of `order_id|payment_id` under the key secret, checked against
     the order we stored.
   - A webhook ([payments](https://razorpay.com/docs/webhooks/payments/), [orders](https://razorpay.com/docs/webhooks/orders/)),
     signed with `X-Razorpay-Signature` (HMAC of the raw body under the webhook secret). The previous secret is accepted
     until a set time, for a rotation.
4. **Exactly once:** whichever proof comes first credits the top-up, under its row lock. The ledger transaction is named
   by the payment, so the other proof posts nothing. Webhooks are handled once by `x-razorpay-event-id`, and none of
   their payload is kept, because it names the payer.
5. **Auto-capture is on** (the dashboard setting in docs/runbooks/payments.md). A payment that is only authorised is
   never credited; the reconciliation reports it.
6. **What we keep:** Razorpay's ids, the method, and for a card only its network and last four digits.

### Invoices and GST

- **Prices are before GST.** Every listing price is stored and quoted before GST. The host earns 80% of that, so "You'll
  earn ₹X" never changes.
- **Renters pay GST on top** (`CreditReservations.renterCost`, used by holds, quotes, metering and leases). Turning GST on
  raises what renters pay and never lowers a host's earnings; a test proves it. The host's pricing screen shows both
  figures (`renterPays` in web/src/lib/hosting.ts).
- **No GSTIN, no GST.** GST is charged only once `legal.invoicing.gstin` is set. Until then every document is a receipt,
  "Not a tax invoice: GST registration pending".
- **The split:** CGST and SGST for a renter in our state, IGST otherwise. The renter's state comes from their billing
  details (`/v1/wallet/billing-details`, required before the first top-up; a GSTIN is checked by its shape and check
  character, and must belong to the state given).
- **Two modes, both built** (`legal.invoicing.mode`):
  - `per_topup` (the default): a tax invoice with each top-up. Its GST is owed then, and rides on the lot as
    `gst_on_unused_credit`, so a refund of unused credit takes it back.
  - `monthly_usage`: a receipt for each top-up, and one tax invoice a month for the credit used.
- **Credit notes:** a refund, or a chargeback lost, issues a credit note against the top-up's document, with its tax in
  the same proportions.
- **Numbering:** one gap-free series per financial year (April to March, India's time), `IDL2627-000001`, drawn from a
  locked counter row in the document's own transaction. The tests issue them from many threads at once.
- **The documents:** each document's JSON is kept whole and never changed. Its PDF is drawn by PDFBox with the embedded
  Plus Jakarta Sans, and is the same bytes every time.
- **TCS isn't built**, and production refuses `tcs: true`.

### Reconciliation

- **What it compares:** every night at 03:15 India time, and when an admin asks for a day (`PaymentReconciliation`), that
  day's payments and refunds are listed from Razorpay ([payments](https://razorpay.com/docs/api/payments/fetch-all-payments/),
  [refunds](https://razorpay.com/docs/api/refunds/fetch-all/), 100 a page) and compared one by one with our top-ups and
  refund parts.
- **Ours as well:** our own records from that day that Razorpay didn't list are fetched by id.
- **What it finds:** missing on our side, missing on theirs, amount, status, captured but not credited, and authorised
  but not captured. Each disagreement is an item (`billing.payment_mismatches`), kept once by its kind and Razorpay's id,
  so running a day twice adds nothing.
- **Two put right where found,** by the same path a webhook takes, and still listed so someone asks why the webhook
  didn't arrive: a captured payment nobody credited (`TopUps.credit`, which checks it as it checks every payment), and a
  refund Razorpay has finished.
- **Closing items:** a later run that finds an item right marks it fixed. An admin may accept one, with a reason; that is
  audited and stays accepted.
- **Watching it:** `billing.payments.reconciliation.open` is the open count, and the PaymentMismatch alert fires on any.
- **Not reconciled yet:** Razorpay's fees and settlements (what reaches the bank account, less fees). That is a later
  step, with the company's bank account.

### Refunds of bought credit

- **Asking:** the renter asks from Credit (`POST /v1/wallet/refund-requests`). A `money` support ticket opens, through
  billing's `MoneyTickets` port, which support implements.
- **Approving:** an admin approves an amount: at most the bought credit not held for a session.
- **At once:** in one transaction, the credit leaves the bought lots, newest first, for `refunds_pending`, with the GST it
  carried. Each payment it came from gets a part.
- **Sending:** each part is refunded against its own payment ([Refunds API](https://razorpay.com/docs/api/refunds/create-normal/)),
  at normal speed, keyed by our receipt, so sending it again refunds nothing twice. A part sent before whose answer was
  lost is found among its payment's refunds.
- **Settling:** `refund.processed` posts the part out to `gateway_clearing` and issues a credit note. `refund.failed`
  brings it back to its lot ([refund webhooks](https://razorpay.com/docs/webhooks/refunds/)).
- **Where it goes:** a refund never goes to another payment method.
- **Deletion** (ADR 0037): `PersonalData.beforeErasure` refunds what's left the same way. The deletion waits until it's
  processed (`refund-in-progress`), and a failed one stops it until support sorts it out (`refund-failed`).

### Reports on a session (disputing a session)

- **One path:** the existing "Report a problem" flow (ADR 0028), not a second one. Its reasons now include "The host
  stopped it" (`host_stopped`).
- **What the admin reads:**
  - the session's metering by both witnesses, its timeline and its ending;
  - its log, through `GET /admin/v1/sessions/{id}/log` with a reason, which is audited, because it is the renter's own
    job's output;
  - the host's record, with every flag on it.
- **Deciding:** the admin decides once: no refund, part or all. A refund is a free lot that never expires, its ledger
  transaction named by the report.
- **Flagging the host:** optionally `flagHost`, a mark on the host's record (`trust.host_flags`) that admins see and the
  host score round will use; the host never sees it.
- **Telling the renter:** every decision is audited. `ReportResolved` emails the renter (the locked "Report answered"
  kind).

### Chargebacks

- **The events:** Razorpay's `payment.dispute.*` webhooks ([dispute webhooks](https://razorpay.com/docs/webhooks/disputes/))
  record a dispute (`billing.chargebacks`).
- **The freeze:** the renter's bought credit, up to the disputed amount, moves to `disputes_frozen`: the disputed
  payment's own lot first, never what a running session holds. Later events freeze more once more is there. Free credit
  isn't frozen: it was never money.
- **The admin item:** the payment, the sessions its credit paid for (each with its metering and, with a reason, its log),
  and the date evidence is due.
- **Evidence** is submitted in Razorpay's dashboard; we don't automate that.
- **The outcome:** once Razorpay says the dispute is final, what was lost is its documented `amount_deducted` (for a lost
  one that says nothing was deducted, the disputed amount; for a won one, nothing).
  - The loss leaves the frozen credit first. Past it, it is `chargeback_losses`. A credit note is issued for what was
    taken back.
  - The rest of the freeze goes back to the lots it came from.
  - A loss suspends the account pending review (identity's `AccountHolds`).
- **Repeats:** events come more than once and out of order. A final dispute never changes again, and every posting is
  named by the dispute.

### Limits and fraud

- **Top-up limits** (`legal.top-ups`, LEGAL(confirm)):
  - each top-up ₹50 to ₹10,000;
  - at most ₹10,000 a day;
  - at most ₹25,000 of bought credit held;
  - for an account younger than 7 days, ₹2,000 a top-up and ₹2,000 a day.
- **Velocity:** more than 5 failed payments in an hour pause top-ups for an hour (`too-many-failed-payments`, with
  Retry-After).

### The ledger

| What | Debit | Credit |
| --- | --- | --- |
| A top-up | `gateway_clearing` | `renter_purchased` (its own lot) |
| … per_topup with GST | `gst_on_unused_credit` (the lot) | `tax_cgst` and `tax_sgst`, or `tax_igst` |
| A session's charge | the renter's lots, free first | `host_payable`, `platform_revenue` (and the GST owed on what wasn't paid in advance) |
| A refund approved | `renter_purchased` (newest lots) | `refunds_pending` |
| … processed | `refunds_pending` | `gateway_clearing` |
| … failed | `refunds_pending` | `renter_purchased` (its lot) |
| A report's give-back | `platform_revenue`, then `host_payable` | `renter_wallet` (a new lot that never expires) |
| A chargeback opened | `renter_purchased` (the payment's lot first) | `disputes_frozen` |
| … lost | `disputes_frozen`, then `chargeback_losses` | `gateway_clearing` |
| … won | `disputes_frozen` | `renter_purchased` (the lots it came from) |
| Free credit expiring | `renter_wallet` | `free_credit_expired` |

`verify-ledger.sh` adds four checks:

- every lot equals its entries and no lot is below zero;
- frozen and refunding credit are never below zero (they sit outside the balance, so with check 3 nothing frozen,
  refunding or reserved is more than the renter has);
- every paid top-up has exactly one credit;
- every processed refund part has exactly one debit.

### The Credit page's own security policy

- **Its own page load:** the Credit page is the only page that loads Checkout, and it is a page load of its own (every
  link to it is a plain link, `AppLink`). A policy belongs to the page that loaded, so Checkout never runs under another
  page's policy, and no other page allows Razorpay's origins.
- **Where the policy lives:** in its page's `<meta>` tag and in `dist/path-headers.json`, which `scripts/serve.ts` and
  the deployment serve.
- **The script:** it loads only when Pay is pressed, so Checkout's script is never part of a first load. A deployed build
  must name Razorpay's own `https://checkout.razorpay.com/v1/checkout.js`.
- **Collecting the origins:** Razorpay doesn't publish the origins Checkout uses. The only guidance is its Cordova guide's
  wildcard for razorpay.com. So the real test-mode check runs a staging build with `IDLEO_CHECKOUT_CSP=report-only`. That
  build allows `https://*.razorpay.com` and sends the strict policy as `Content-Security-Policy-Report-Only`, so the
  browser names every origin the strict list lacks. Then `CHECKOUT_ORIGINS` in `web/src/security/headers.ts` becomes
  exactly what that check found, and production enforces it (a production build refuses report-only).
- **The origins so far:**

| Directive | Origin | Where it came from |
| --- | --- | --- |
| `script-src` | `https://checkout.razorpay.com` | Razorpay's Standard Checkout page: the script's own address |
| `frame-src` | `https://api.razorpay.com` | Razorpay's IPs and certificates page names it as the API host; Checkout's window is to be confirmed in the real check |
| `connect-src`, `img-src` | none yet | to be filled from the real check's report-only messages |

Open question: the list above is what the documentation gives; it becomes final only after Nikki's real test-mode check
(docs/runbooks/payments.md, "The real test-mode check").

### The Credit page's speed

- **Why it costs one more file:** a page load of its own, with its own policy, can't be drawn from code the app already
  loaded. Opening Credit downloads the app's first load and Credit's own chunk: one more file than Home. On Lighthouse's
  simulated phone, that file arrives on the same six connections as the rest, so the main script lands a round later:
  2,104 ms against Home's 1,953 (measured by replaying Lighthouse's own simulation on the saved runs).
- **With figures kept from an earlier visit** (how every screen is measured): the credit paints from them, and the
  history and every other request start after the paint. That's 1,952 ms, inside the 2.0 s budget.
- **With none kept:** the credit waits for `/v1/wallet`. Its preflight already runs beside the scripts, but the request
  can't: it needs the ID token, which only the sign-in adapter holds, so it starts when the main script has run. That
  makes about 2.4 s.
- **Not done:** an early request from the page's HTML, a preload, or a second script that asks the adapter early. None
  can send the bearer token without a second path to it (cookies are ruled out), and every path to the token stays in
  `src/auth`.
- **How it's measured:** the no-figure load is report-only, with a 2.5 s ceiling like Chrome's own throttling
  (web/README.md, "Measuring Lighthouse").

### Testing without real money

- **The fake:** a stateful fake Razorpay (`FakeRazorpay`), a WireMock extension on the control plane's **test** classpath
  only. No fake code is in the control plane.
- **What it answers:** orders, payment fetch and lists, capture states, refunds (processed, pending, failed), disputes, and
  webhooks sent to the control plane. Every one is built from Razorpay's documented samples.
- **The local stack:** the stack and the end-to-end test run it as `FakeRazorpayServer` (`scripts/lib/stack.sh`), with a
  fake Checkout (`/v1/checkout.js`) that pays through it. The whole flow runs offline.

### Production

`ProductionSettingsGuard`:

- **Refuses** Razorpay's test keys in production, live keys anywhere else, an API other than Razorpay's, a missing
  webhook secret, live keys without the GSTIN, legal name and address invoices carry, and TCS on.
- **Allows** production without keys: payments are then off, and top-ups answer `payments-unavailable`.

## Alternatives considered

- **Crediting on Checkout's handler alone:** the browser can be forged or lost. Credit only from a payment the server has
  fetched captured, from either proof.
- **One balance with no lots:** bought credit couldn't be told from free at refund time, free credit couldn't expire, and
  GST couldn't follow the credit it was paid on.
- **Listing prices including GST:** turning GST on would have cut every host's earnings by 18%. Prices before GST keep
  hosts' earnings unchanged (Nikki's decision).
- **A second "dispute" path beside reports:** two queues for one question. Reports were extended instead.
- **Freezing free credit for a chargeback:** it isn't money, and taking it back recovers nothing.
- **Allowing Razorpay's origins on every page:** every page would trust a third party's scripts it never needs.
- **Payment Links or Razorpay's hosted pages:** a page outside ours to keep in step, and no part of it in our tests.

## Consequences

- **Fixed rules:** money in is proven before it's credited, exactly once, and every movement is a named ledger
  transaction that the restore drill's `verify-ledger.sh` checks.
- **A daily job:** reconciliation is a daily job with an alert, and its items are an admin's to close.
- **Before going live:** the CA's answers decide the GST mode and timing. The live account needs the company's KYC.
  Webhooks need a public address, and auto-capture must be set in the dashboard (docs/runbooks/payments.md, "Going live").
- **Watch:** the Credit page's policy against Checkout changing its origins. A change shows as a blocked script, frame or
  connection in the browser's console and as failed payments (the PaymentsFailing alert).
- **Later:** fees and settlements, payouts to hosts (a later round), and TCS if it applies.

## The LEGAL(confirm) table

| Setting | Proposed | The question to ask |
| --- | --- | --- |
| `legal.credit.free-expires-after` | 90 days | Our CA and lawyer: may free promotional credit expire after 90 days, and must the Terms say so before it's given? Is closed-loop credit outside the RBI's prepaid payment instrument rules as we use it? |
| `legal.invoicing.mode` | `per_topup` | Our CA: is GST due when credit is bought (a tax invoice with each top-up, per_topup) or when it's used (a receipt at top-up, a monthly tax invoice for use, monthly_usage)? Is a top-up of closed-loop credit an advance for a supply of services, or a voucher? |
| `legal.invoicing.gst-rate-percent` | 18 | Our CA: is 18% right for GPU compute rented by the minute, and under which SAC (`legal.invoicing.sac`)? |
| `legal.invoicing.prices-include-gst` | listing before GST, GST added | Our CA: may renters be shown the price with GST added on the booking screen, and must the screen show the GST separately? |
| `legal.invoicing.state-code` | 29 (Karnataka) | Our CA: which state is our place of business for the CGST/SGST against IGST split? |
| `legal.invoicing.tcs` | off, not built | Our CA: as a marketplace paying hosts, do we collect TCS under section 52 of the CGST Act, and when? |
| `legal.invoicing.number-prefix` | IDL | Our CA: is `IDL2627-000001` (16 characters, one series a financial year) a valid invoice series? |
| `legal.top-ups.*` | ₹50 to ₹10,000 a top-up, ₹10,000 a day, ₹25,000 held, ₹2,000 for the first 7 days | Our lawyer and Razorpay: are these limits right for closed-loop credit and our risk, and does holding up to ₹25,000 change anything under RBI rules? |
| GST timing in per_topup | owed at top-up, carried on the lot, taken back on refund | Our CA: in per_topup, if a renter is refunded unused credit, is a credit note against the top-up's invoice the right way to reduce the GST already paid? |
| A lost chargeback | credit note for the bought credit taken back; the rest a loss | Our CA: does a lost chargeback need a credit note, and is the part beyond the frozen credit a deductible loss with no GST adjustment? |

## Razorpay's pages relied on

- [Orders API: create](https://razorpay.com/docs/api/orders/create/)
- [Payments API: fetch with id](https://razorpay.com/docs/api/payments/fetch-with-id/), [fetch all](https://razorpay.com/docs/api/payments/fetch-all-payments/)
- [Refunds API: create a normal refund](https://razorpay.com/docs/api/refunds/create-normal/), [fetch all](https://razorpay.com/docs/api/refunds/fetch-all/), [fetch a payment's refunds](https://razorpay.com/docs/api/refunds/fetch-multiple-refund-payment/)
- [Webhooks: payments](https://razorpay.com/docs/webhooks/payments/), [orders](https://razorpay.com/docs/webhooks/orders/), [refunds](https://razorpay.com/docs/webhooks/refunds/), [disputes](https://razorpay.com/docs/webhooks/disputes/), [best practices](https://razorpay.com/docs/webhooks/best-practices/)
- [Standard Checkout](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/)
- [Payment capture settings](https://razorpay.com/docs/payments/payments/capture-settings/)
- [Disputes](https://razorpay.com/docs/payments/disputes/), [Disputes API](https://razorpay.com/docs/api/disputes/)
- [Test mode](https://razorpay.com/docs/payments/payments/test-mode/), [test cards](https://razorpay.com/docs/payments/payments/test-card-details/), [test UPI](https://razorpay.com/docs/payments/payments/test-upi-details/)
- [IPs and certificates](https://razorpay.com/docs/security/whitelists/), [Cordova integration](https://razorpay.com/docs/payments/payment-gateway/cordova-integration/) (the CSP guidance)
