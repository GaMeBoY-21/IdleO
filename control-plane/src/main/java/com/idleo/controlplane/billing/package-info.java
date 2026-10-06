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
 * (docs/runbooks/granting-credits.md), never by an endpoint.
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
 *       session_charge}, {@code session_refund}), reference and time. {@code limit} is 1 to 50 (default 20);
 *       {@code nextCursor} fetches the next page, and is null on the last.
 *   <li>{@code GET /v1/hosts/{id}/earnings?cursor=…&limit=…}, for the host's owner (anyone else gets a 404):
 *       {@code balancePaise}, the host's payable balance, and {@code history}, its entries paged the same way,
 *       of kind {@code session_earning} or {@code lost_minutes_refunded}. Nothing is paid out or held in
 *       escrow yet: payouts come with the payment partner.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.billing;

import org.springframework.modulith.ApplicationModule;
