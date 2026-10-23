package com.idleo.controlplane.billing.internal;

/** The ledger's accounts and their kinds (ADR 0014, ADR 0039). */
enum Account {
    /** Liability: free credit we owe the renter service for (grants, promotions, give-backs), in lots. */
    RENTER_WALLET("renter_wallet", true, true),
    /** Liability: credit the renter bought, in lots, one per payment. */
    RENTER_PURCHASED("renter_purchased", true, true),
    /** Liability: we owe the host money. Raised by credits. */
    HOST_PAYABLE("host_payable", true, true),
    /** Revenue. Raised by credits. */
    PLATFORM_REVENUE("platform_revenue", false, true),
    /** Asset: money Razorpay holds for us. Raised by debits. */
    GATEWAY_CLEARING("gateway_clearing", false, false),
    /** Expense: what free credit costs us. Raised by debits. */
    PROMOTIONS("promotions", false, false),
    /** Liability: bought credit on its way back to the payment it came from. Raised by credits. */
    REFUNDS_PENDING("refunds_pending", true, true),
    /** Liability: credit frozen while a chargeback on the renter's payment is open. Raised by credits. */
    DISPUTES_FROZEN("disputes_frozen", true, true),
    /** Liability: central GST owed. Raised by credits. */
    TAX_CGST("tax_cgst", false, true),
    /** Liability: state GST owed. Raised by credits. */
    TAX_SGST("tax_sgst", false, true),
    /** Liability: integrated GST owed. Raised by credits. */
    TAX_IGST("tax_igst", false, true),
    /** Contra-liability: GST already owed on bought credit that isn't spent yet (per_topup invoices). Raised by debits. */
    GST_ON_UNUSED_CREDIT("gst_on_unused_credit", false, false),
    /** Free credit that expired unspent, the other side of what promotions cost. Raised by credits. */
    FREE_CREDIT_EXPIRED("free_credit_expired", false, true),
    /** Expense: a lost chargeback the renter's credit didn't cover. Raised by debits. */
    CHARGEBACK_LOSSES("chargeback_losses", false, false);

    final String dbValue;
    /** Owned accounts belong to a renter or host; the platform's accounts have no owner. */
    final boolean owned;
    /** Whether a credit raises the account's balance (liabilities and revenue) rather than a debit. */
    final boolean raisedByCredits;

    Account(String dbValue, boolean owned, boolean raisedByCredits) {
        this.dbValue = dbValue;
        this.owned = owned;
        this.raisedByCredits = raisedByCredits;
    }

    /** A renter's spendable credit: the wallet cache's balance is the sum of these two. */
    boolean isRenterCredit() {
        return this == RENTER_WALLET || this == RENTER_PURCHASED;
    }

    /** Whether an entry on this account names its lot: always for a renter's credit, and for GST paid ahead on it. */
    boolean lotted() {
        return isRenterCredit() || this == GST_ON_UNUSED_CREDIT;
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
