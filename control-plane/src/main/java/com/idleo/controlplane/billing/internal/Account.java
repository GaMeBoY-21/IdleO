package com.idleo.controlplane.billing.internal;

/**
 * The ledger's accounts (ADR 0014, ADR 0039, ADR 0040, ADR 0055): the chart of accounts as the database's check
 * constraint has it, each with its type, its normal side and what it holds in plain words, for the console.
 */
enum Account {
    RENTER_WALLET(
            "renter_wallet",
            Type.LIABILITY,
            true,
            true,
            "Free credit we owe renters service for: grants, promotions and give-backs, in lots."),
    RENTER_PURCHASED(
            "renter_purchased", Type.LIABILITY, true, true, "Credit renters bought, in lots, one for each payment."),
    HOST_PAYABLE(
            "host_payable",
            Type.LIABILITY,
            true,
            true,
            "What we owe each machine's owner for its sessions, not paid yet."),
    PLATFORM_REVENUE("platform_revenue", Type.INCOME, false, true, "Our share of every session: the take."),
    GATEWAY_CLEARING(
            "gateway_clearing", Type.ASSET, false, false, "Money Razorpay holds for us, until it settles to the bank."),
    PROMOTIONS("promotions", Type.EXPENSE, false, false, "What the free credit we give away costs us."),
    REFUNDS_PENDING(
            "refunds_pending",
            Type.LIABILITY,
            true,
            true,
            "Bought credit on its way back to the payment it came from."),
    DISPUTES_FROZEN(
            "disputes_frozen",
            Type.LIABILITY,
            true,
            true,
            "Credit frozen while a chargeback on the renter's payment is open."),
    TAX_CGST("tax_cgst", Type.LIABILITY, false, true, "Central GST we owe the government."),
    TAX_SGST("tax_sgst", Type.LIABILITY, false, true, "State GST we owe the government."),
    TAX_IGST("tax_igst", Type.LIABILITY, false, true, "Integrated GST we owe the government."),
    GST_ON_UNUSED_CREDIT(
            "gst_on_unused_credit",
            Type.LIABILITY,
            false,
            false,
            "A contra liability: GST already owed on bought credit that isn't spent yet, taken back if it's refunded."),
    FREE_CREDIT_EXPIRED(
            "free_credit_expired",
            Type.INCOME,
            false,
            true,
            "Free credit that expired unspent: breakage, the other side of what promotions cost."),
    CHARGEBACK_LOSSES(
            "chargeback_losses",
            Type.EXPENSE,
            false,
            false,
            "Lost chargebacks the renter's credit didn't cover, and clawbacks an admin waived."),
    PAYOUTS_IN_FLIGHT("payouts_in_flight", Type.LIABILITY, true, true, "Earnings on their way to an owner's UPI ID."),
    PAYOUTS_PAID(
            "payouts_paid",
            Type.ASSET,
            true,
            true,
            "A contra asset: what has left our RazorpayX account for each owner, the sum of their processed payouts."),
    TDS_PAYABLE(
            "tds_payable", Type.LIABILITY, false, true, "Tax deducted at source from payouts, owed to the government."),
    HOST_RECEIVABLE(
            "host_receivable",
            Type.ASSET,
            true,
            false,
            "Earnings refunded after they were paid out, owed back by the owner from later earnings."),
    EARNINGS_FORFEITED(
            "earnings_forfeited",
            Type.INCOME,
            false,
            true,
            "Earnings an owner gave up in writing when deleting the account without a payout method."),
    GATEWAY_FEES("gateway_fees", Type.EXPENSE, false, false, "Razorpay's fees on what it settles to us."),
    GST_INPUT_CREDIT(
            "gst_input_credit", Type.ASSET, false, false, "The GST charged on Razorpay's fees, which we claim back."),
    WRITE_OFFS("write_offs", Type.EXPENSE, false, false, "Small differences written off by an approved adjustment.");

    /** What an account is, on the balance sheet or in the profit and loss. */
    enum Type {
        ASSET,
        LIABILITY,
        INCOME,
        EXPENSE;

        String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    final String dbValue;
    final Type type;
    /** Owned accounts belong to a renter or host; the platform's accounts have no owner. */
    final boolean owned;
    /** Whether a credit raises the account's balance (liabilities and revenue) rather than a debit. */
    final boolean raisedByCredits;
    /** What it holds, in plain words, for the console's chart of accounts. */
    final String description;

    Account(String dbValue, Type type, boolean owned, boolean raisedByCredits, String description) {
        this.dbValue = dbValue;
        this.type = type;
        this.owned = owned;
        this.raisedByCredits = raisedByCredits;
        this.description = description;
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
