# Payouts

How money goes out to hosts (ADR 0040): weekly to a checked UPI ID through RazorpayX's test mode now, and what changes
to go live. No real money moves until the company has a RazorpayX business account with its KYC and current account,
and the CA has answered the LEGAL(confirm) table in ADR 0040.

## The keys

The control plane reads these from its environment, never from a file in git, apart from money in's Razorpay keys:

| Variable | What it is |
| --- | --- |
| `RAZORPAYX_KEY_ID` | RazorpayX's API key id, `rzp_test_…` in test mode (`rzp_live_…` live, production only) |
| `RAZORPAYX_KEY_SECRET` | Its secret |
| `RAZORPAYX_WEBHOOK_SECRET` | The secret set on the payouts webhook in RazorpayX's dashboard |
| `RAZORPAYX_ACCOUNT_NUMBER` | The business account payouts and UPI checks are paid from: RazorpayX's dashboard, My Account & Settings, Banking (test mode's differs from live's) |
| `PAYOUTS_PAN_KEY` | 32 random bytes in base64 (`openssl rand -base64 32`) that PANs are sealed under. Production refuses to start without it; losing it makes every stored PAN unreadable, so keep it in the secret store with a backup |

Without the key id, its secret and the account number, payouts are off: nothing is checked or paid, and UPI checks
answer `payouts-unavailable`. Locally, put them in the gitignored `control-plane/.env` (`scripts/run-local.sh` loads
it); the stack (`./start.sh`) and the end-to-end test use the fake RazorpayX and never read `.env`, and their PAN key
is `private/dev-ca/pan.key`, made by `control-plane/scripts/dev-ca.sh`.

Where to make test keys (Nikki): sign in at https://x.razorpay.com, switch to **Test Mode** (the profile menu), then
**My Account & Settings → Developer Controls → API Keys → Generate Key**. Test mode has its own dummy balance: **+ Add
test balance** on the dashboard puts in any amount (https://razorpay.com/docs/x/dashboard/test-mode/).

`ProductionSettingsGuard` refuses: test keys in production; live keys anywhere else; an API other than
`https://api.razorpay.com`; keys without `RAZORPAYX_WEBHOOK_SECRET` or `RAZORPAYX_ACCOUNT_NUMBER`; live keys while
`LEGAL_TDS_CONFIRMED` isn't `true`; no `PAYOUTS_PAN_KEY`, or the tests' committed one; `payouts.pan.verifier: fake`.

## The dashboard's settings

In RazorpayX's dashboard (test mode first, the same again live):

- **Webhook** (**My Account & Settings → Developer Controls → Webhooks → Add webhook**;
  https://razorpay.com/docs/webhooks/setup-edit-payouts/): `https://<api>/v1/payouts/razorpayx/webhook`, the secret you
  put in `RAZORPAYX_WEBHOOK_SECRET`, and these events:
  - `payout.queued`, `payout.initiated`, `payout.processed`, `payout.reversed`, `payout.failed`, `payout.updated`
    (`payout.failed` is mandatory with the Payout APIs);
  - `payout.pending`, `payout.rejected` (only with Approval Workflow, which we don't use);
  - `fund_account.validation.completed`, `fund_account.validation.failed`.
  The test-mode webhook form asks for an OTP: `754081`. RazorpayX refuses `localhost` and the common tunnels; use the
  staging address.
- **IP allowlist** (RazorpayX requires it for API calls): add the control plane's egress addresses
  (https://razorpay.com/docs/x/dashboard/allowlist-ip/).
- **Approval Workflow:** off. Payouts go out as the run makes them; an admin's checks are in our console.
- **Contact types:** none to add: we use `vendor`, which exists by default.

## The local stack

`./start.sh` and the end-to-end test start the fake RazorpayX on its own port (`FakeRazorpayXServer`, from the test
classpath, never in the jar) and point the control plane at it with test keys. It moves each payout to its end by
itself and sends the webhooks, steered by the UPI ID:

| UPI ID | What happens |
| --- | --- |
| anything, say `asha@okicici` | Check: active, registered to the PAN's name, score 100. Payout: processed |
| `lowmatch@…` | Check: active, score 42: an admin approves it |
| `invalid@…` | Check: the bank says it isn't active |
| `slow@…` | Check: answered later (tests only) |
| `payoutfails@…` | Payout: failed (the money comes back; the next run tries again) |
| `reversed@…` | Payout: processed, then reversed (the UPI ID becomes suspect) |

PANs on the local stack are checked by the fake: any well-formed PAN is verified, `ABCPE0000F` (digits 0000) is no PAN,
`ABCPE9999F` (digits 9999) someone else's, which goes to an admin.

## The real test-mode check

Before going live, with Nikki's own test keys and a staging stack that RazorpayX can reach:

1. Set the four `RAZORPAYX_*` variables to test-mode values, and `PAYOUTS_PAN_KEY`. Add the webhook as above. Add test
   balance.
2. As a host with a machine that has earned (any session), open Hosting, Payout details. Give a PAN; it waits for an
   admin (no provider yet): confirm it in the console's Payouts, Verification, with a reason.
3. Check a real UPI ID of yours (test mode validates real VPAs too). Note what RazorpayX returned: the registered name,
   the status, and whether the answer came as `validation_results` or `results`, and the score as a number or a string
   (ADR 0040, "Watch"). Confirm it.
4. In the console's Payouts, Run now (the minimum is ₹100: earn that, or wait out the window on a staging stack with
   `--legal.payouts.dispute-window=2m`). The payout is `processing`: test mode never moves it on by itself.
5. In RazorpayX's dashboard, open the payout and move it to **processed** (test mode's own control). Within seconds our
   payout is processed, the host's Earnings shows it, and the email arrives. Check `X-Payout-Idempotency` in the
   dashboard's API logs.
6. Do it again with **reversed**, and with a new payout moved to **failed**: the money is back in Earnings each time,
   and after the reversal the UPI ID asks to be checked again.
7. The next morning, the console's Payouts, Reconciliation: 0 open. Record what you saw in ADR 0040.

## The weekly run

Every Monday at 10:00 India time, everyone with an active UPI ID (and a verified PAN) who has at least ₹100 available is
paid, one payout for all their machines; the day before, at 10:00, RazorpayX's balance is checked against it. The
console's Payouts shows each run (how many people, payouts and rupees) and each payout with its status. Run now starts
one at once, with a reason; nobody is paid twice, because a payout on its way stops the next.

## Funding payouts

The run pays from `RAZORPAYX_ACCOUNT_NUMBER`'s balance. When the balance check finds too little (PayoutBalanceLow),
move money from the company's current account into the RazorpayX account before Monday 10:00. A payout made while it's
short is queued by RazorpayX (`queue_if_low_balance`) and goes out when the money arrives; a queued payout is cancelled
by RazorpayX after 3 months, and its money comes back to the host's earnings.

## When payouts fail

A payout that fails, is rejected or cancelled puts its money (and its tax) back in the host's earnings at once, and the
host is told RazorpayX's reason in plain words. The next run tries again. To pay one host again now: the console's
Payouts, Retry, with their user id and a reason. Never retry a payout by editing the database: each payout is a ledger
posting that `verify-ledger.sh` checks.

## Suspended hosts

An admin who suspends an account (Users, Suspend) says **why**: *misconduct as a host* or *anything else* (ADR 0041). For
misconduct as a host, **no payout is made to that account** (the weekly run, Run now, a retry and the final payout before a
deletion all pass over it) until the suspension is lifted; the earnings keep accruing and are paid, in full, at the next run
after Reinstate. For any other reason, and for an account the abuse watch held, payouts go on as before. The cause is in the
suspension's audit row (`user.suspend`, `after.cause`) beside the admin's reason, and the Users screen says "Suspended for:
Misconduct as a host" while it holds. A held account's payouts show as `ACCOUNT_HELD` where an admin's retry says why
nothing was paid.

## Reversals

A reversed payout came back from the bank, even after it was processed. Its money is back in the host's earnings, and
their UPI ID is suspect: no payout goes to it until they check a UPI ID again (the same one is fine, and isn't held). If
several reverse at once on one bank, it's the bank: tell those hosts and wait.

## Verification

The console's Payouts, Verification lists PANs and UPI IDs waiting for an admin:

- **A PAN** waits for everyone until a provider is chosen (no PAN check online yet). Compare the name with the PAN's
  holder (ask the host for a photo of the card through Help if unsure); Verify or Reject, with a reason the host reads
  when rejected. Reveal shows the whole PAN, with a reason, and is audited.
- **A UPI ID** whose bank name matched the PAN's below 80: Approve if it's plainly the same person (initials, a middle
  name), else Reject.

A host changing their UPI ID needs a fresh sign-in, and payouts are held 48 hours with an email: if a host writes that
they didn't make the change, suspend the account (Users) and reject the method before the hold ends.

## Clawbacks

A report refunded after the session's earnings were paid out takes the host's share back from their unpaid earnings
first; the rest is owed and taken from their next earnings before any payout. The console's Clawbacks lists them;
Waive writes off what's still owed into chargeback losses, with a reason. Nothing is ever taken from a host's UPI ID.

## Reconciliation

Every night at 03:45 India time (and Run in the console), RazorpayX's payouts of the day before are compared with ours:

- `missing_ours`: RazorpayX paid something we have no payout for. Someone paid from RazorpayX's dashboard: find out who;
  accept it with the reason.
- `missing_theirs`: a payout RazorpayX doesn't have. Never accepted (out of reach for a day): look at its last error;
  the resend keeps trying. Gone at RazorpayX: ask RazorpayX support.
- `amount`: RazorpayX paid a different amount. Stop and ask RazorpayX; don't pay the difference by hand.
- `status`: it ended differently at RazorpayX (processed here, failed there, say). Read its status details in the
  dashboard; a real failure after processing is put right only by RazorpayX moving it to reversed.

## The TDS export

Each quarter, the console's Tax: the quarter (`2026-27-Q3` is October to December 2026) and a reason. The CSV has each
payout that didn't come back, deducted in the quarter: the host's whole PAN and name, the section and rate, gross, tax,
net and the dates. It's audited, because it holds every PAN; send it to the CA through the company's secure channel,
never email. Hosts download their own yearly statement (PDF or CSV) from Hosting, Earnings.

## The Money dashboard

Grafana's **Money** dashboard (`ops/grafana/dashboards/idleo-money.json`) reads this module's meters and billing's: top-ups
paid and failed by method and the seconds to credit, refunds processed and failed, chargebacks by event, payouts by status and
the days from the oldest earning to paid, what was paid and clawed back in rupees, the RazorpayX balance, and both nightly
reconciliations. The two reconciliation tiles and the signature tile must be 0; the alerts in `ops/alerts/payments.yaml` and
`payouts.yaml` page on the same meters.

## Going live

1. The company's RazorpayX business account, with its KYC done and its current account linked; live API keys; the
   webhook (live mode) and its secret; the IP allowlist.
2. The CA's answers to ADR 0040's LEGAL(confirm) table, put into `legal.tds` and `legal.payouts`, then
   `LEGAL_TDS_CONFIRMED=true`.
3. A PAN provider chosen (ADR 0040 lists three) and built behind `PanVerifier`, or an admin's time for every PAN.
4. `PAYOUTS_PAN_KEY` in the secret store, with its backup.
5. The live variables in production, the real test-mode check done once more live with ₹1 to an IdleO person's UPI ID,
   and the first Monday watched in the console.
