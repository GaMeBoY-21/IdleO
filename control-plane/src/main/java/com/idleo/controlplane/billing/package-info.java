/**
 * The billing module. Owns: wallets, the double-entry ledger, free credit grants, session charges and
 * holds, and later payment intents and gateway webhooks. Its tables live in the {@code billing} database schema, and no
 * other module reads them (ADR 0014).
 *
 * <p>Every paisa moves through one posting primitive, which writes a balanced, append-only ledger
 * transaction and updates the wallet cache in the same database transaction. The database itself
 * refuses an unbalanced transaction, any change to an entry, and a negative wallet. A nightly job checks
 * every wallet against the ledger.
 *
 * <p>Free credit is granted and reversed by operator commands in the {@code ops} profile
 * (docs/runbooks/granting-credits.md), or by an admin in the console (ADR 0027), through the same service, never by a
 * renter's endpoint.
 *
 * <p>Metering settles a session's minutes through {@link com.idleo.controlplane.billing.SessionCharges}: one
 * balanced transaction debits the renter's wallet, credits each host's payable its share and the platform's
 * revenue the commission, and moves the session's hold, all in one database transaction (ADR 0017). Holds
 * live in {@link com.idleo.controlplane.billing.CreditReservations}, with every change in an append-only audit
 * that the nightly check reconciles.
 *
 * <p>Endpoints, in integer paise, each for its owner only. Every error is an RFC 9457 problem detail.
 *
 * <ul>
 *   <li>{@code GET /v1/wallet}: {@code balancePaise}, {@code reservedPaise} and {@code availablePaise};
 *       zeros before the first credit.
 *   <li>{@code GET /v1/wallet/transactions?cursor=…&limit=…}: their wallet's entries, newest first, each
 *       with its amount, direction, kind ({@code free_credit}, {@code free_credit_reversed}, {@code
 *       session_charge}, {@code session_refund}, {@code dispute_refund}), reference and time. {@code limit} is 1 to 50 (default 20);
 *       {@code nextCursor} fetches the next page, and is null on the last.
 *   <li>{@code GET /v1/hosts/{id}/earnings?cursor=…&limit=…}, for the host's owner (anyone else gets a 404):
 *       {@code balancePaise}, the host's payable balance, and {@code history}, its entries paged the same way,
 *       of kind {@code session_earning}, {@code lost_minutes_refunded} or {@code refunded_to_renter}. Nothing is paid out or held in
 *       payouts move it on (ADR 0040).
 *   <li>{@code GET /v1/earnings?cursor=…&limit=…}: the same across every machine the user owns, removed ones
 *       too (ADR 0023): the total, this week's (Monday to Sunday, IST), the minutes rented this week, each of the
 *       last 7 days, each machine's total, and a paged history in which lost-time givebacks and refunds to renters
 *       ("Refunded to renter") are negative entries, and an entry whose session has an open report is {@code
 *       payoutHeld}.
 *       Read only from the ledger, so it always equals it; the service and minutes beside each amount come from
 *       the {@link com.idleo.controlplane.billing.EarningDetails} port, which metering implements. {@code
 *       payoutsStart} is {@code weekly}. Payouts, recoveries and clawbacks aren't earning and aren't listed; a refund of earnings
 *       already paid out is labelled {@code Recovered: refund for session … on …}.
 * </ul>
 *
 * <p>{@link com.idleo.controlplane.billing.HostEarnings} gives the sessions module what a host earned from each
 * session, from the same entries.
 *
 * <p>For the trust module's reports (ADR 0028): {@link com.idleo.controlplane.billing.SessionRefunds} reads what a
 * session paid from its own ledger entries and refunds up to that, from the platform's commission first and then the
 * hosts' shares, as one balanced {@code dispute_refund} transaction; {@link com.idleo.controlplane.billing.PayoutHolds}
 * records, in {@code billing.payout_holds}, the sessions whose host earnings are held while a report is open, for the
 * payouts to come.
 *
 * <p>Paying hosts (ADR 0040): {@link com.idleo.controlplane.billing.HostPayouts} is the ledger's side, for the payouts
 * module: what an owner's machines have pending (inside {@code legal.payouts.dispute-window} or held by a report) and
 * available, and the postings that move it into {@code payouts_in_flight}, on to {@code payouts_paid}, or back. A report's
 * refund of earnings already paid out is a clawback ({@code billing.clawbacks}): the owner's balance covers it as far as
 * it goes and the rest is a {@code host_receivable}, recovered before their next payout and never debited from their
 * UPI ID; {@link com.idleo.controlplane.billing.EarningsClawedBack} tells them. {@code GET /admin/v1/clawbacks} lists them
 * and {@code POST /admin/v1/clawbacks/{id}/waive} writes off what's owed into {@code chargeback_losses}, audited. Top-ups
 * past {@code legal.payouts.renter-pan-above-paise} in a financial year need a verified PAN ({@link
 * com.idleo.controlplane.billing.VerifiedPans}, which payouts implements).
 *
 * <p>The admin console (ADR 0027): {@code POST /admin/v1/credit-grants} (Idempotency-Key, the grant's own key) and
 * {@code POST /admin/v1/credit-grants/{id}/reverse}, each with a reason and audited; {@code GET
 * /admin/v1/credit-grants?user=}; {@code GET /admin/v1/ledger} by transaction or by account and owner; and {@code GET
 * /admin/v1/reconciliation}, the latest runs of the wallet check, each kept with its mismatches.
 */
@ApplicationModule
package com.idleo.controlplane.billing;

import org.springframework.modulith.ApplicationModule;
