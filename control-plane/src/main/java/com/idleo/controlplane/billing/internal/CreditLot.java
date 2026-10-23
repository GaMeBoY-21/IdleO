package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One lot of a renter's credit (ADR 0039): free (a grant, a promotion, a give-back) or bought (one payment), what it
 * started at and what's left, and when free credit expires ({@code null}: never). Written only by {@link Ledger}.
 */
record CreditLot(
        UUID id,
        UUID userId,
        Kind kind,
        String sourceType,
        UUID sourceId,
        Paise amount,
        Paise remaining,
        @Nullable Instant expiresAt,
        Instant createdAt) {

    enum Kind {
        FREE("free", Account.RENTER_WALLET),
        PURCHASED("purchased", Account.RENTER_PURCHASED);

        final String dbValue;
        /** The renter account this kind of credit is kept in. */
        final Account account;

        Kind(String dbValue, Account account) {
            this.dbValue = dbValue;
            this.account = account;
        }

        static Kind fromDb(String value) {
            for (var kind : values()) {
                if (kind.dbValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown lot kind " + value);
        }
    }

    Account account() {
        return kind.account;
    }
}
