package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One side of a ledger transaction. {@code ownerId} is set for owned accounts only; {@code lotId} names the lot of
 * credit a renter's entry draws from or adds to (ADR 0039), and the bought lot GST paid ahead belongs to.
 */
record LedgerEntry(
        Account account,
        @Nullable UUID ownerId,
        Paise amount,
        Direction direction,
        @Nullable UUID lotId) {

    static LedgerEntry debit(Account account, @Nullable UUID ownerId, Paise amount) {
        return new LedgerEntry(account, ownerId, amount, Direction.DEBIT, null);
    }

    static LedgerEntry credit(Account account, @Nullable UUID ownerId, Paise amount) {
        return new LedgerEntry(account, ownerId, amount, Direction.CREDIT, null);
    }

    /** A debit from one lot of the renter's credit. */
    static LedgerEntry debitLot(Account account, UUID ownerId, Paise amount, UUID lotId) {
        return new LedgerEntry(account, ownerId, amount, Direction.DEBIT, lotId);
    }

    /** A credit to one lot of the renter's credit. */
    static LedgerEntry creditLot(Account account, UUID ownerId, Paise amount, UUID lotId) {
        return new LedgerEntry(account, ownerId, amount, Direction.CREDIT, lotId);
    }

    /** What this entry does to its account's credit-raised balance (a renter's wallet or a lot). */
    Paise signedForCreditAccount() {
        return direction == Direction.CREDIT ? amount : amount.negate();
    }
}
