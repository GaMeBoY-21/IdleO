/**
 * The payouts module (ADR 0040). Owns: paying hosts their earnings, weekly, to a UPI ID through RazorpayX (test mode until
 * the company's live account exists), with tax deducted at source; checking a host's PAN and UPI ID first; and
 * reconciling RazorpayX with our payouts. Its tables live in the {@code payouts} database schema, and no other module
 * reads them.
 *
 * <p>The money itself moves only through billing's ledger ({@link com.idleo.controlplane.billing.HostPayouts}): earnings
 * are pending for {@code legal.payouts.dispute-window} and while a report holds them, then available; a payout moves
 * what's available (after recovering anything owed from a clawback) into {@code payouts_in_flight} before RazorpayX is
 * asked to pay, with {@code reference_id} and {@code X-Payout-Idempotency} both our payout's id. RazorpayX's webhooks
 * ({@code POST /v1/payouts/razorpayx/webhook}, signed, each handled once) and a poll move it on: processed is paid,
 * failed, rejected and cancelled come back to the earnings for the next run, and reversed comes back too and marks the
 * UPI ID suspect.
 *
 * <ul>
 *   <li>Hosting, Payout details: {@code GET /v1/payout-details}, {@code PUT /v1/payout-details/pan}, {@code POST
 *       /v1/payout-details/upi-checks} and {@code …/{id}/confirm} (a fresh sign-in; a change holds payouts 48 hours and
 *       is emailed: {@link com.idleo.controlplane.payouts.PayoutMethodChanged}).
 *   <li>Hosting, Earnings: {@code GET /v1/payouts/summary}, {@code GET /v1/payouts}, {@code GET
 *       /v1/payouts/statement}; {@code POST /v1/payouts/forfeit} to give earnings up in writing before deleting the
 *       account.
 *   <li>The admin console: {@code /admin/v1/payouts} (runs, payouts, retry, verification, reconciliation and the
 *       quarter's tax export), each action with a reason and audited.
 * </ul>
 *
 * <p>It implements billing's {@link com.idleo.controlplane.billing.VerifiedPans} (renters buying a lot give a PAN) and the
 * shared kernel's {@code PersonalData}: deleting an account waits for a final payout. It announces {@link
 * com.idleo.controlplane.payouts.PayoutSettled} and {@link com.idleo.controlplane.payouts.PayoutMethodChanged}, which
 * the notifications module turns into messages. Nothing but notifications depends on it.
 */
@ApplicationModule
package com.idleo.controlplane.payouts;

import org.springframework.modulith.ApplicationModule;
