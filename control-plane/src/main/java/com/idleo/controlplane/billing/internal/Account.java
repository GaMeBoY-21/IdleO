package com.idleo.controlplane.billing.internal;

/** The ledger's accounts and their kinds (ADR 0014). */
enum Account {
    /** Liability: we owe the renter service. Raised by credits. */
    RENTER_WALLET("renter_wallet", true),
    /** Liability: we owe the host money. Raised by credits. */
    HOST_PAYABLE("host_payable", true),
    /** Revenue. Raised by credits. */
    PLATFORM_REVENUE("platform_revenue", false),
    /** Asset: money the payment gateway holds for us. Raised by debits. */
    GATEWAY_CLEARING("gateway_clearing", false),
    /** Expense: what free credit costs us. Raised by debits. */
    PROMOTIONS("promotions", false);

    final String dbValue;
    /** Owned accounts belong to a renter or host; the platform's accounts have no owner. */
    final boolean owned;

    Account(String dbValue, boolean owned) {
        this.dbValue = dbValue;
        this.owned = owned;
    }

    static Account fromDb(String value) {
        for (var account : values()) {
            if (account.dbValue.equals(value)) {
                return account;
            }
        }
        throw new IllegalArgumentException("unknown ledger account " + value);
    }
}
