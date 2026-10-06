package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One side of a ledger transaction. {@code ownerId} is set for owned accounts only. */
record LedgerEntry(Account account, @Nullable UUID ownerId, Paise amount, Direction direction) {

    static LedgerEntry debit(Account account, @Nullable UUID ownerId, Paise amount) {
        return new LedgerEntry(account, ownerId, amount, Direction.DEBIT);
    }

    static LedgerEntry credit(Account account, @Nullable UUID ownerId, Paise amount) {
        return new LedgerEntry(account, ownerId, amount, Direction.CREDIT);
    }

    /** What this entry does to its account's credit-raised balance (a renter's wallet). */
    Paise signedForCreditAccount() {
        return direction == Direction.CREDIT ? amount : amount.negate();
    }
}
