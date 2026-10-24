# Payments

How money comes in (ADR 0039): Razorpay's test mode now, and what changes to go live. No real money moves until the
company, its KYC and the CA's answers exist (the LEGAL(confirm) table in ADR 0039).

## The keys

The control plane reads three settings from its environment, never from a file in git:

| Variable | What it is |
| --- | --- |
| `RAZORPAY_KEY_ID` | The key id, `rzp_test_…` in test mode (`rzp_live_…` live, production only) |
| `RAZORPAY_KEY_SECRET` | Its secret: it signs our calls and checks Checkout's payment signature |
| `RAZORPAY_WEBHOOK_SECRET` | The secret set on the webhook in the dashboard |

Optionally `RAZORPAY_PREVIOUS_WEBHOOK_SECRET` and `RAZORPAY_PREVIOUS_WEBHOOK_SECRET_UNTIL` (an ISO time), for a rotation.
Without a key id, payments are off and top-ups answer `payments-unavailable`. Locally, put them in the gitignored
`control-plane/.env` (`scripts/run-local.sh` loads it); the stack (`./start.sh`) and the end-to-end test use the fake
Razorpay instead and never read `.env`.

`ProductionSettingsGuard` refuses test keys in production, live keys anywhere else, an API other than
`https://api.razorpay.com`, live keys without `RAZORPAY_WEBHOOK_SECRET`, live keys without `INVOICING_GSTIN`,
`INVOICING_LEGAL_NAME` and `INVOICING_ADDRESS`, and `legal.invoicing.tcs: true`.

## The dashboard's settings

In Razorpay's dashboard (test mode first, the same again in live mode):

- **Payment capture:** automatic, immediately
  ([capture settings](https://razorpay.com/docs/payments/payments/capture-settings/)). A payment left authorised is never
  credited; the reconciliation lists it.
- **Webhook:** `https://<api>/v1/payments/razorpay/webhook`, with the secret you put in `RAZORPAY_WEBHOOK_SECRET`, and
  these events:
  - `payment.captured`, `payment.failed`, `order.paid`;
  - `refund.processed`, `refund.failed`;
  - `payment.dispute.created`, `payment.dispute.under_review`, `payment.dispute.action_required`,
    `payment.dispute.won`, `payment.dispute.lost`, `payment.dispute.closed`.
- **Refunds:** normal speed (we ask for it on every refund).

## The local stack

`./start.sh` and `scripts/e2e/agent-online.sh` run the fake Razorpay on port 8091 from the control plane's test classpath
(`FakeRazorpayServer`; its log is `logs/fake-razorpay.log`). It answers Razorpay's API from the documented samples, sends
Razorpay's webhooks to the control plane after each payment, and serves a fake Checkout (`/v1/checkout.js`). On Credit,
Pay opens a small dialog: "Pay with test UPI", "Pay with a test card", "Fail the payment" or "Close". Nothing leaves the
machine.

## The real test-mode check

Run by hand once with real test keys, to see a real Checkout and to collect the origins it uses for the Credit page's
policy. Webhooks needn't reach your Mac: the credit arrives through the server's own fetch of the payment.

1. Put your test keys in `control-plane/.env` and start the control plane with `control-plane/scripts/run-local.sh`.
2. Build the web app with Razorpay's own Checkout and the Credit page's policy in report-only mode, and serve it with
   `pnpm serve` (which sends the page's own headers, `dist/path-headers.json`, where a report-only policy has to be):

   ```
   cd web
   VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js IDLEO_CHECKOUT_CSP=report-only pnpm build && pnpm serve
   ```

   with your usual local settings in `web/.env.local` (`env.local.example`). In report-only mode the Credit page allows
   all of Razorpay's domains (`https://*.razorpay.com`, the pattern Razorpay's Cordova guide gives) so Checkout works,
   and reports what the strict list would have blocked. A staging build may do the same; production refuses it.
3. Open Credit, save your state, choose ₹100, press Pay. In Checkout:
   - UPI: `success@razorpay` pays and `failure@razorpay` fails
     ([test UPI](https://razorpay.com/docs/payments/payments/test-upi-details/));
   - a card: one from [Razorpay's test cards](https://razorpay.com/docs/payments/payments/test-card-details/), then
     Success on the mock bank page.
4. "Adding ₹100…" turns into "₹100.00 added." once the server has fetched the captured payment. Time it; it goes in the
   report.
5. With the browser's console open, collect every `Content-Security-Policy-Report-Only` message. Each names a directive (`frame-src`, `connect-src`, `img-src`, `script-src`, `style-src`) and an
   origin. Also note any "Permissions policy violation: payment" line: the Credit page denies `payment=()`.
6. Put exactly those origins in `CHECKOUT_ORIGINS` (`web/src/security/headers.ts`), each with where it came from, and
   update the table in ADR 0039. Build again without `IDLEO_CHECKOUT_CSP` (enforcing) and pay once more: the console
   must show no blocked line.

**Webhooks, optionally:** to try real test webhooks, expose the local API with a tunnel (for example
`cloudflared tunnel --url http://localhost:8080`). Set the tunnel's https address plus `/v1/payments/razorpay/webhook`
as a test-mode webhook with the events above and its secret in `RAZORPAY_WEBHOOK_SECRET`. A payment then shows
`credited_by = webhook` or `checkout`, whichever came first; the other changes nothing.

## Rotating the webhook secret

1. Set the new secret in the dashboard.
2. Set `RAZORPAY_PREVIOUS_WEBHOOK_SECRET` to the old one and `RAZORPAY_PREVIOUS_WEBHOOK_SECRET_UNTIL` to a day from now,
   and `RAZORPAY_WEBHOOK_SECRET` to the new one, then deploy. Events Razorpay retries under the old secret are still
   accepted until then.
3. After that time, remove the previous secret.

## Reconciliation

Every night at 03:15 India time the control plane compares yesterday's payments and refunds with Razorpay. In the admin
console, Payments shows the runs and what they found; Run a day now compares any day again (running it twice adds
nothing). An open item fires the PaymentMismatch alert.

| Kind | What to do |
| --- | --- |
| Razorpay has it; we don't | A payment or refund that isn't ours (a payment page made in the dashboard, say). Find it in Razorpay's dashboard; refund it there if it shouldn't exist; accept the item with what you did. |
| We have it; Razorpay doesn't | We credited a payment, or recorded a refund, Razorpay doesn't know. Check the id in the dashboard. If it's truly missing, reverse the credit by a correction (docs/runbooks/granting-credits.md) and write it down. |
| A different amount | Razorpay's amount, or its refunded amount, isn't ours. Compare both in the dashboard and the admin console's Ledger; correct the ledger by a new transaction, never by an edit. |
| A different state | Razorpay says failed where we credited, or a refund finished that we hadn't heard of (the reconciliation settles that one itself). Check why the webhook didn't arrive (its deliveries in the dashboard). |
| Captured, not credited | The reconciliation has credited it, by the same check as every payment. Find out why the webhook and Checkout's confirmation both missed it. A second captured payment on an order already paid is never credited: refund it from the dashboard. |
| Authorised, not captured | Auto-capture should have captured it: check the setting. Razorpay refunds an uncaptured payment by itself after a few days; accept the item once you've checked. |

Razorpay's fees and settlements (what reaches the bank account) aren't reconciled yet: a later step.

## Refunds of bought credit

- A renter asks from Credit, "Refund unused credit". It arrives in the admin console's Refunds, and as a money message in
  Tickets.
- Approve an amount: at most the bought credit not held for a session, which the screen shows. It leaves their credit at
  once and is refunded to the payments it came from, newest first, never to another method. Or decline it, with a
  reason the renter reads.
- A part Razorpay can't refund (`refund.failed`) comes back to the renter's credit. The refund then shows failed: write
  to the renter, and try again once the cause is clear.
- Deleting an account refunds the bought credit the same way, and the deletion waits until it's back with the person.

## Reports on a session

The renter's "Report a problem" (ADR 0028) arrives in the admin console's Reports. Read its minutes by both witnesses and
its timeline. Read its log with "Read the log", which asks why: it is the renter's own job's output, and every read is
audited. Read the host's record and its flags. Then decide:

- **No refund**, with a reason the renter reads.
- **A refund of part or all**, given back as free credit that never expires, from the platform's commission first.

Tick "Flag the host's record" when the host was at fault (it stopped the session, the GPU was far slower than listed).
The renter is emailed the decision.

## Chargebacks

A renter's bank disputing a payment arrives as `payment.dispute.created`, fires the ChargebackOpened alert, and freezes
the renter's bought credit up to the disputed amount.

1. Open the admin console's Chargebacks: open ones by the date evidence is due.
2. The record shows the payment, the sessions its credit paid for (each with its timeline, minutes and, with a reason,
   its log), and the due date.
3. **Submit the evidence in Razorpay's dashboard** (Disputes, the dispute, Submit evidence) before it's due: the payment,
   the sessions' minutes and charges, their logs where they help, and the Terms the renter accepted. We don't submit it
   from here.
4. Razorpay's outcome arrives by webhook:
   - **won:** the frozen credit comes back to the renter;
   - **lost:** what Razorpay deducted leaves the frozen credit first; anything past it is our loss
     (`chargeback_losses`); a credit note is issued; the account is suspended pending review.
5. Review a suspended account in Users: reinstate it or not, with a reason.

## Payments failing

The PaymentsFailing alert: more than 30% of an hour's payments failed, once at least 10 were tried.

- **One method only** (UPI, say): Razorpay's status page; tell renters on ours.
- **Every method:**
  - Is Checkout loading? A blocked script on the Credit page shows in the browser's console; it means Razorpay changed
    its origins. Repeat steps 5 and 6 of the real test-mode check.
  - Are orders refused? Look for `payments-unavailable` among the API's errors, and Razorpay's answers in the control
    plane's log.

## Going live

1. The company is registered, Razorpay's KYC is done, and live mode is on.
2. The CA has answered the LEGAL(confirm) table (ADR 0039); set `legal.invoicing.*` to match: `INVOICING_GSTIN`,
   `INVOICING_LEGAL_NAME`, `INVOICING_ADDRESS`, `INVOICING_STATE_CODE`, `INVOICING_SAC`, and the mode.
3. In live mode, set the dashboard's settings above (capture, webhook and its events), with a new webhook secret.
4. Set the live keys and the webhook secret in production's secrets, never anywhere else.
5. Check the Credit page's enforced policy (the real check, in live mode, with a real ₹1 payment refunded straight after).
6. Watch the first day: the reconciliation's 03:15 run must find nothing.
