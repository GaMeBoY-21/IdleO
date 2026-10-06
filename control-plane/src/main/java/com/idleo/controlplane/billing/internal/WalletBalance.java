package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.util.UUID;

/** A renter's wallet, as cached from the ledger. */
record WalletBalance(UUID userId, Paise balance, Paise reserved) {

    static WalletBalance empty(UUID userId) {
        return new WalletBalance(userId, Paise.ZERO, Paise.ZERO);
    }

    /** What the renter can still spend or reserve. */
    Paise available() {
        return balance.minus(reserved);
    }
}
