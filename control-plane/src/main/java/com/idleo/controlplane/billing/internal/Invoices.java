package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Invoices, receipts and credit notes (ADR 0039), as {@code legal.invoicing} says:
 *
 * <ul>
 *   <li>{@code per_topup}: a tax invoice for each top-up, its GST inside what was paid; until we're registered for
 *       GST, a receipt that says "Not a tax invoice: GST registration pending" and charges none.
 *   <li>{@code monthly_usage}: a receipt for each top-up, and on the first of each month a tax invoice for the credit
 *       each renter used the month before (the charges' prices and their GST).
 *   <li>A credit note against any of them, for a refund or an admin's correction: a document is never cancelled by
 *       deleting it, and its number is never reused.
 * </ul>
 *
 * <p>Numbers are one gap-free sequence a financial year (April to March, in India's time): the year's counter row is
 * locked while a number is taken, in the same transaction as the document, so concurrent top-ups never skip or repeat
 * one. The document is kept whole as issued (seller, buyer, lines, totals), and the PDF and the JSON are made from it.
 */
@Service
class Invoices {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String NOT_A_TAX_INVOICE = "Not a tax invoice: GST registration pending";

    private static final Logger log = LoggerFactory.getLogger(Invoices.class);

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final Gst gst;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    Invoices(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            Gst gst,
            AuditTrail audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.gst = gst;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    enum Kind {
        TAX_INVOICE("tax_invoice", "Tax invoice"),
        RECEIPT("receipt", "Receipt"),
        USAGE_INVOICE("usage_invoice", "Tax invoice for credit used"),
        CREDIT_NOTE("credit_note", "Credit note");

        final String dbValue;
        final String title;

        Kind(String dbValue, String title) {
            this.dbValue = dbValue;
            this.title = title;
        }

        static Kind fromDb(String value) {
            for (var kind : values()) {
                if (kind.dbValue.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown invoice kind " + value);
        }
    }

    /** A document as issued. */
    record Invoice(
            UUID id,
            String number,
            Kind kind,
            UUID userId,
            @Nullable UUID topUpId,
            @Nullable UUID creditNoteFor,
            Paise taxable,
            Paise cgst,
            Paise sgst,
            Paise igst,
            Paise total,
            ObjectNode document,
            Instant issuedAt) {}

    /** The amounts of one document. */
    record Totals(Paise taxable, Gst.Split tax) {

        Paise total() {
            return taxable.plus(tax.total());
        }
    }

    /** The document for a top-up just credited, in the caller's transaction: once a top-up. */
    @Transactional(propagation = Propagation.MANDATORY)
    Invoice forTopUp(TopUps.TopUp topUp) {
        var settings = gst.settings();
        boolean taxInvoice = settings.mode() == LegalSettings.InvoiceMode.PER_TOPUP && settings.registered();
        var kind = taxInvoice ? Kind.TAX_INVOICE : Kind.RECEIPT;
        var existing = jdbc.sql("select id from billing.invoices where topup_id = :topUpId and kind = :kind")
                .param("topUpId", topUp.id())
                .param("kind", kind.dbValue)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return find(existing.get()).orElseThrow();
        }
        var tax = taxInvoice ? gst.split(topUp.userId(), gst.taxInside(topUp.amount())) : Gst.Split.NONE;
        var totals = new Totals(topUp.amount().minus(tax.total()), tax);
        var line = taxInvoice
                ? "IdleO credit, prepaid, for GPU compute on IdleO only"
                : settings.mode() == LegalSettings.InvoiceMode.MONTHLY_USAGE && settings.registered()
                        ? "IdleO credit, prepaid; GST is invoiced each month on the credit used"
                        : "IdleO credit, prepaid, for GPU compute on IdleO only";
        var payment = json.createObjectNode();
        payment.put("razorpayPaymentId", topUp.paymentId());
        payment.put("method", topUp.method());
        return issue(kind, topUp.userId(), topUp.id(), null, null, null, totals, List.of(line), payment);
    }

    /**
     * A credit note for {@code amount} of an invoice (a refund, or an admin's correction), its GST in the same
     * proportion as the invoice's. Never more than the invoice less earlier credit notes against it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Invoice creditNote(UUID invoiceId, Paise amount, String reason) {
        var original = find(invoiceId).orElseThrow(() -> new IllegalArgumentException("no invoice " + invoiceId));
        if (original.kind() == Kind.CREDIT_NOTE) {
            throw new IllegalArgumentException("a credit note can't be credited");
        }
        long credited = jdbc.sql("""
                        select coalesce(sum(total_paise), 0) from billing.invoices
                        where credit_note_for = :id and kind = 'credit_note'
                        """).param("id", invoiceId).query(Long.class).single();
        if (!amount.isPositive() || amount.value() + credited > original.total().value()) {
            throw new IllegalArgumentException("a credit note is more than zero and at most what's left of invoice "
                    + original.number() + ": " + (original.total().value() - credited) + " paise");
        }
        // The same share of each tax as the whole invoice had.
        Gst.Split tax = Gst.Split.NONE;
        if (original.total().isPositive()) {
            long cgst = Math.multiplyExact(original.cgst().value(), amount.value())
                    / original.total().value();
            long sgst = Math.multiplyExact(original.sgst().value(), amount.value())
                    / original.total().value();
            long igst = Math.multiplyExact(original.igst().value(), amount.value())
                    / original.total().value();
            tax = new Gst.Split(Paise.of(cgst), Paise.of(sgst), Paise.of(igst));
        }
        var totals = new Totals(amount.minus(tax.total()), tax);
        var note = json.createObjectNode();
        note.put("against", original.number());
        note.put("reason", reason.strip());
        return issue(
                Kind.CREDIT_NOTE,
                original.userId(),
                original.topUpId(),
                original.id(),
                null,
                null,
                totals,
                List.of("Credit against " + original.number() + ": " + reason.strip()),
                note);
    }

    /** An admin's credit note, audited with its reason in the same transaction. */
    Invoice creditNoteByAdmin(UUID invoiceId, Paise amount, String reason, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var note = creditNote(invoiceId, amount, reason);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "invoice.credit_note",
                    "invoice",
                    invoiceId.toString(),
                    reason.strip(),
                    Map.of(),
                    Map.of("creditNote", note.number(), "amountPaise", amount.value())));
            return note;
        }));
    }

    /**
     * {@code monthly_usage}: on the first of each month, a tax invoice for each renter's credit used the month before,
     * from the ledger's session charges (their prices and the GST they carried). Once a renter a month.
     */
    @Scheduled(cron = "${billing.invoices.usage-cron}", zone = "Asia/Kolkata")
    void monthly() {
        if (gst.settings().mode() == LegalSettings.InvoiceMode.MONTHLY_USAGE) {
            usage(YearMonth.from(clock.instant().atZone(INDIA)).minusMonths(1));
        }
    }

    /** The usage invoices for {@code month}; how many were issued now. */
    int usage(YearMonth month) {
        var start = month.atDay(1).atStartOfDay(INDIA).toInstant();
        var end = month.plusMonths(1).atDay(1).atStartOfDay(INDIA).toInstant();
        // Each renter's charges in the month: what the hosts and the platform took (the price before GST) and the
        // GST owed or carried.
        var used = jdbc.sql("""
                        select r.owner_id as user_id,
                               coalesce(sum(o.amount_paise) filter (where o.account in ('host_payable', 'platform_revenue')
                                                                     and o.direction = 'credit'), 0)
                             - coalesce(sum(o.amount_paise) filter (where o.account in ('host_payable', 'platform_revenue')
                                                                     and o.direction = 'debit'), 0) as taxable,
                               coalesce(sum(case o.direction when 'credit' then o.amount_paise else -o.amount_paise end)
                                        filter (where o.account = 'tax_cgst'), 0) as cgst,
                               coalesce(sum(case o.direction when 'credit' then o.amount_paise else -o.amount_paise end)
                                        filter (where o.account = 'tax_sgst'), 0) as sgst,
                               coalesce(sum(case o.direction when 'credit' then o.amount_paise else -o.amount_paise end)
                                        filter (where o.account = 'tax_igst'), 0) as igst
                        from (select distinct txn_id, owner_id from billing.ledger_entries
                              where ref_type in ('session', 'session_refund')
                                and account in ('renter_wallet', 'renter_purchased')
                                and created_at >= :start and created_at < :end) r
                        join billing.ledger_entries o on o.txn_id = r.txn_id
                        group by r.owner_id
                        """)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end))
                .query((rs, n) -> new Object[] {
                    rs.getObject("user_id", UUID.class),
                    rs.getLong("taxable"),
                    rs.getLong("cgst"),
                    rs.getLong("sgst"),
                    rs.getLong("igst")
                })
                .list();
        int issued = 0;
        for (var row : used) {
            var userId = (UUID) row[0];
            long taxable = (long) row[1];
            if (taxable <= 0) {
                continue;
            }
            var totals = new Totals(
                    Paise.of(taxable),
                    new Gst.Split(Paise.of((long) row[2]), Paise.of((long) row[3]), Paise.of((long) row[4])));
            var made = transactions.execute(status -> {
                var exists = jdbc.sql("""
                                select exists (select 1 from billing.invoices
                                               where user_id = :userId and kind = 'usage_invoice'
                                                 and period_start = :start)
                                """)
                        .param("userId", userId)
                        .param("start", Timestamp.from(start))
                        .query(Boolean.class)
                        .single();
                if (exists) {
                    return false;
                }
                issue(
                        Kind.USAGE_INVOICE,
                        userId,
                        null,
                        null,
                        start,
                        end,
                        totals,
                        List.of("GPU compute on IdleO, "
                                + month.getMonth()
                                        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                                + " " + month.getYear()),
                        json.createObjectNode());
                return true;
            });
            if (Boolean.TRUE.equals(made)) {
                issued++;
            }
        }
        log.info("Issued {} usage invoices for {}", issued, month);
        return issued;
    }

    private Invoice issue(
            Kind kind,
            UUID userId,
            @Nullable UUID topUpId,
            @Nullable UUID creditNoteFor,
            @Nullable Instant periodStart,
            @Nullable Instant periodEnd,
            Totals totals,
            List<String> lines,
            ObjectNode extra) {
        var issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        var year = financialYear(issuedAt);
        jdbc.sql("insert into billing.invoice_counters (financial_year, last_number) values (:year, 0)"
                        + " on conflict (financial_year) do nothing")
                .param("year", year)
                .update();
        long sequence = jdbc.sql("""
                        update billing.invoice_counters set last_number = last_number + 1
                        where financial_year = :year returning last_number
                        """).param("year", year).query(Long.class).single();
        var settings = gst.settings();
        var number = settings.numberPrefix() + year.substring(2, 4) + year.substring(5, 7) + "-"
                + String.format("%06d", sequence);
        var id = ids.newId();
        var document =
                document(kind, number, issuedAt, userId, periodStart, periodEnd, totals, lines, extra, creditNoteFor);
        jdbc.sql("""
                        insert into billing.invoices
                            (id, number, financial_year, sequence, kind, user_id, topup_id, credit_note_for,
                             period_start, period_end, taxable_paise, cgst_paise, sgst_paise, igst_paise, total_paise,
                             document, issued_at)
                        values (:id, :number, :year, :sequence, :kind, :userId, :topUpId, :creditNoteFor,
                                :periodStart, :periodEnd, :taxable, :cgst, :sgst, :igst, :total,
                                cast(:document as jsonb), :issuedAt)
                        """)
                .param("id", id)
                .param("number", number)
                .param("year", year)
                .param("sequence", sequence)
                .param("kind", kind.dbValue)
                .param("userId", userId)
                .param("topUpId", topUpId)
                .param("creditNoteFor", creditNoteFor)
                .param("periodStart", periodStart == null ? null : Timestamp.from(periodStart))
                .param("periodEnd", periodEnd == null ? null : Timestamp.from(periodEnd))
                .param("taxable", totals.taxable().value())
                .param("cgst", totals.tax().cgst().value())
                .param("sgst", totals.tax().sgst().value())
                .param("igst", totals.tax().igst().value())
                .param("total", totals.total().value())
                .param("document", json.writeValueAsString(document))
                .param("issuedAt", Timestamp.from(issuedAt))
                .update();
        return find(id).orElseThrow();
    }

    /** The financial year, April to March in India's time: {@code 2026-27} for any day from 1 April 2026 to 31 March 2027. */
    static String financialYear(Instant at) {
        var day = LocalDate.ofInstant(at, INDIA);
        int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private ObjectNode document(
            Kind kind,
            String number,
            Instant issuedAt,
            UUID userId,
            @Nullable Instant periodStart,
            @Nullable Instant periodEnd,
            Totals totals,
            List<String> lines,
            ObjectNode extra,
            @Nullable UUID creditNoteFor) {
        var settings = gst.settings();
        var root = json.createObjectNode();
        root.put("kind", kind.dbValue);
        root.put("title", settings.registered() ? kind.title : (kind == Kind.CREDIT_NOTE ? "Credit note" : "Receipt"));
        root.put("number", number);
        root.put("issuedAt", issuedAt.toString());
        if (!settings.registered()) {
            root.put("notice", NOT_A_TAX_INVOICE);
        }
        var seller = root.putObject("seller");
        seller.put("name", settings.legalName().isBlank() ? "IdleO" : settings.legalName());
        seller.put("address", settings.address());
        seller.put("gstin", settings.registered() ? settings.gstin() : null);
        seller.put("stateCode", settings.stateCode());
        seller.put("state", GstStates.name(settings.stateCode()).orElse(""));
        var details = details(userId);
        var buyer = root.putObject("buyer");
        buyer.put("accountId", userId.toString());
        buyer.put(
                "businessName",
                details.map(BillingDetails.Details::businessName).orElse(null));
        buyer.put("gstin", details.map(BillingDetails.Details::gstin).orElse(null));
        var place = gst.stateOf(userId);
        buyer.put("stateCode", place);
        buyer.put("state", GstStates.name(place).orElse(""));
        root.put("placeOfSupply", place + " " + GstStates.name(place).orElse(""));
        if (periodStart != null && periodEnd != null) {
            root.put("periodStart", periodStart.toString());
            root.put("periodEnd", periodEnd.toString());
        }
        var rate = settings.registered() ? settings.gstRatePercent() : 0;
        var items = root.putArray("lines");
        for (var text : lines) {
            var line = items.addObject();
            line.put("description", text);
            line.put("sac", settings.sac());
            line.put("taxablePaise", totals.taxable().value());
            line.put("gstPercent", totals.tax().total().isPositive() ? rate : 0);
            line.put("cgstPaise", totals.tax().cgst().value());
            line.put("sgstPaise", totals.tax().sgst().value());
            line.put("igstPaise", totals.tax().igst().value());
            line.put("totalPaise", totals.total().value());
        }
        var sums = root.putObject("totals");
        sums.put("taxablePaise", totals.taxable().value());
        sums.put("cgstPaise", totals.tax().cgst().value());
        sums.put("sgstPaise", totals.tax().sgst().value());
        sums.put("igstPaise", totals.tax().igst().value());
        sums.put("totalPaise", totals.total().value());
        root.set(kind == Kind.CREDIT_NOTE ? "creditNote" : "payment", extra);
        if (creditNoteFor != null) {
            root.put("creditNoteFor", creditNoteFor.toString());
        }
        root.put("closedLoop", "IdleO credit is spent only on IdleO; it can't be withdrawn as cash or transferred.");
        return root;
    }

    private Optional<BillingDetails.Details> details(UUID userId) {
        return jdbc.sql("select state_code, gstin, business_name from billing.billing_details where user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> new BillingDetails.Details(
                        rs.getString("state_code"), rs.getString("gstin"), rs.getString("business_name")))
                .optional();
    }

    /** The tax invoice or receipt issued for a top-up, which its credit notes go against. */
    Optional<Invoice> ofTopUp(UUID topUpId) {
        return jdbc.sql(
                        "select * from billing.invoices where topup_id = :topUpId and kind in ('tax_invoice', 'receipt')")
                .param("topUpId", topUpId)
                .query((rs, n) -> invoice(rs))
                .optional();
    }

    Optional<Invoice> find(UUID id) {
        return jdbc.sql("select * from billing.invoices where id = :id")
                .param("id", id)
                .query((rs, n) -> invoice(rs))
                .optional();
    }

    List<Invoice> ofUser(UUID userId) {
        return jdbc.sql("select * from billing.invoices where user_id = :userId order by issued_at desc, id desc")
                .param("userId", userId)
                .query((rs, n) -> invoice(rs))
                .list();
    }

    /** For the admin console: by number, user, or the latest. */
    List<Invoice> search(@Nullable String number, @Nullable UUID userId, int limit) {
        var conditions = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        if (number != null && !number.isBlank()) {
            conditions.add("number = :number");
            params.put("number", number.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (userId != null) {
            conditions.add("user_id = :userId");
            params.put("userId", userId);
        }
        var sql = "select * from billing.invoices"
                + (conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions))
                + " order by issued_at desc, id desc limit :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        for (var param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        return statement.query((rs, n) -> invoice(rs)).list();
    }

    private Invoice invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invoice(
                rs.getObject("id", UUID.class),
                rs.getString("number"),
                Kind.fromDb(rs.getString("kind")),
                rs.getObject("user_id", UUID.class),
                rs.getObject("topup_id", UUID.class),
                rs.getObject("credit_note_for", UUID.class),
                Paise.of(rs.getLong("taxable_paise")),
                Paise.of(rs.getLong("cgst_paise")),
                Paise.of(rs.getLong("sgst_paise")),
                Paise.of(rs.getLong("igst_paise")),
                Paise.of(rs.getLong("total_paise")),
                (ObjectNode) json.readTree(rs.getString("document")),
                rs.getTimestamp("issued_at").toInstant());
    }
}
