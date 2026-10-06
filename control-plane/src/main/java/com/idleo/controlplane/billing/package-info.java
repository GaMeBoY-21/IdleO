/**
 * The billing module. Owns: wallets, the double-entry ledger, free credit grants, and later settlements,
 * payment intents and gateway webhooks. Its tables live in the {@code billing} database schema, and no
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
 * <p>Endpoints, for the signed-in renter only, in integer paise. Every error is an RFC 9457 problem
 * detail.
 *
 * <ul>
 *   <li>{@code GET /v1/wallet}: {@code balancePaise}, {@code reservedPaise} and {@code availablePaise};
 *       zeros before the first credit.
 *   <li>{@code GET /v1/wallet/transactions?cursor=…&limit=…}: their wallet's entries, newest first, each
 *       with its amount, direction, kind ({@code free_credit}, {@code free_credit_reversed}, later {@code
 *       session_charge}), reference and time. {@code limit} is 1 to 50 (default 20); {@code nextCursor}
 *       fetches the next page, and is null on the last.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.billing;

import org.springframework.modulith.ApplicationModule;
