package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * GST (ADR 0039), the one place it's worked out, from {@code legal.invoicing}. Nothing is charged until we're
 * registered (a GSTIN is set). Then a renter pays the listing price plus GST, rounded to the paisa, and a host still
 * earns its share of the price before it, so turning GST on raises what renters pay and never lowers a host's earnings.
 * GST is CGST and SGST, half each, when the renter's state (their billing details; ours when they have none) is ours,
 * and IGST when it isn't.
 *
 * <p>When it's owed depends on the invoice mode. {@code per_topup}: a top-up's tax invoice makes the GST in what was paid
 * owed at once (the price includes it), against {@code gst_on_unused_credit} for that bought lot; as the lot's credit is
 * spent, refunded or lost, its share of that GST goes with it ({@link #riding}). {@code monthly_usage}: GST is owed as
 * credit is spent. Either way a session's charge says what GST it carried: the part not already covered by the lots it
 * drew from is owed then.
 */
@Component
class Gst {

    private final LegalSettings.Invoicing invoicing;
    private final JdbcClient jdbc;

    Gst(LegalSettings legal, JdbcClient jdbc) {
        this.invoicing = legal.invoicing();
        this.jdbc = jdbc;
    }

    boolean charged() {
        return invoicing.registered() && invoicing.gstRatePercent() > 0;
    }

    boolean perTopUp() {
        return invoicing.mode() == LegalSettings.InvoiceMode.PER_TOPUP;
    }

    int gstPercent() {
        return charged() ? invoicing.gstRatePercent() : 0;
    }

    Paise renterCost(Paise net) {
        return net.plus(taxOn(net));
    }

    /** GST on {@code net}, rounded half up to the paisa; zero while none is charged. */
    Paise taxOn(Paise net) {
        if (!charged() || !net.isPositive()) {
            return Paise.ZERO;
        }
        return Paise.of((Math.multiplyExact(net.value(), invoicing.gstRatePercent()) + 50) / 100);
    }

    /** The GST inside {@code gross}, a price that includes it: {@code gross} less its value before tax, rounded. */
    Paise taxInside(Paise gross) {
        if (!charged() || !gross.isPositive()) {
            return Paise.ZERO;
        }
        int divisor = 100 + invoicing.gstRatePercent();
        long taxable = (Math.multiplyExact(gross.value(), 100) + divisor / 2) / divisor;
        return gross.minus(Paise.of(taxable));
    }

    /** How GST splits for a renter: CGST and SGST for our own state, IGST for another. */
    record Split(Paise cgst, Paise sgst, Paise igst) {

        static final Split NONE = new Split(Paise.ZERO, Paise.ZERO, Paise.ZERO);

        Paise total() {
            return cgst.plus(sgst).plus(igst);
        }
    }

    /** The place of supply: the renter's state from their billing details, else ours. */
    String stateOf(UUID userId) {
        return jdbc.sql("select state_code from billing.billing_details where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(invoicing.stateCode());
    }

    Split split(UUID userId, Paise tax) {
        if (!tax.isPositive()) {
            return Split.NONE;
        }
        if (stateOf(userId).equals(invoicing.stateCode())) {
            var cgst = Paise.of(tax.value() / 2);
            return new Split(cgst, tax.minus(cgst), Paise.ZERO);
        }
        return new Split(Paise.ZERO, Paise.ZERO, tax);
    }

    /** The entries that make {@code tax} owed (credits) or no longer owed (debits, when {@code owed} is false). */
    List<LedgerEntry> owed(UUID userId, Paise tax, boolean owed) {
        var split = split(userId, tax);
        var entries = new ArrayList<LedgerEntry>();
        for (var part : Map.of(
                        Account.TAX_CGST, split.cgst(),
                        Account.TAX_SGST, split.sgst(),
                        Account.TAX_IGST, split.igst())
                .entrySet()) {
            if (part.getValue().isPositive()) {
                entries.add(
                        owed
                                ? LedgerEntry.credit(part.getKey(), null, part.getValue())
                                : LedgerEntry.debit(part.getKey(), null, part.getValue()));
            }
        }
        entries.sort(java.util.Comparator.comparing(LedgerEntry::account));
        return entries;
    }

    /**
     * The GST already owed on bought credit that rides with {@code amount} of it as it leaves the lot ({@code leaving})
     * or comes back to it: the lot's share of the GST its top-up made owed, in proportion, and all that's left of it
     * when the lot is emptied, so a lot spent to nothing has carried exactly its GST. Zero for free credit and outside
     * {@code per_topup}.
     */
    Paise riding(CreditLot lot, Paise amount, boolean leaving) {
        if (lot.kind() != CreditLot.Kind.PURCHASED || !amount.isPositive()) {
            return Paise.ZERO;
        }
        var balances = jdbc.sql("""
                        select coalesce(sum(amount_paise) filter (where direction = 'debit' and ref_type = 'topup'), 0),
                               coalesce(sum(case direction when 'debit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries
                        where account = 'gst_on_unused_credit' and lot_id = :lotId
                        """)
                .param("lotId", lot.id())
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        long atTopUp = balances[0];
        long outstanding = balances[1];
        if (atTopUp == 0) {
            return Paise.ZERO;
        }
        long share = Math.multiplyExact(amount.value(), atTopUp) / lot.amount().value();
        if (leaving) {
            return Paise.of(lot.remaining().equals(amount) ? outstanding : Math.min(share, outstanding));
        }
        return Paise.of(Math.min(share, atTopUp - outstanding));
    }

    LegalSettings.Invoicing settings() {
        return invoicing;
    }
}
