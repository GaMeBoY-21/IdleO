package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Csv;
import com.idleo.controlplane.billing.TaxExport;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Billing's files for the CA (ADR 0055): the invoice register and credit notes, GSTR-1's data (B2B and B2C, by rate and
 * place of supply, with the SAC code on each invoice) as JSON for the GST portal's offline tool and as CSV, the month's
 * GST summary against the ledger, and the ledger's own: the trial balance, the journal and the settlement
 * reconciliation. CA(confirm): the SAC code, the place of supply and each format are the CA's to confirm.
 */
@Configuration(proxyBeanMethods = false)
class BillingTaxExports {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The month's first and next first moment, in India. */
    private static Timestamp[] range(YearMonth month) {
        return new Timestamp[] {
            Timestamp.from(month.atDay(1).atStartOfDay(Periods.INDIA).toInstant()),
            Timestamp.from(
                    month.plusMonths(1).atDay(1).atStartOfDay(Periods.INDIA).toInstant())
        };
    }

    /** Paise as the GST portal writes money: rupees, two places. */
    private static BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String INVOICES = """
            select i.number, i.kind, i.issued_at, i.user_id, i.taxable_paise, i.cgst_paise, i.sgst_paise, i.igst_paise,
                   i.total_paise, i.document->'buyer'->>'gstin' as buyer_gstin,
                   i.document->'buyer'->>'stateCode' as place, i.document->'lines'->0->>'sac' as sac,
                   coalesce((i.document->'lines'->0->>'gstPercent')::int, 0) as rate, o.number as against
            from billing.invoices i left join billing.invoices o on o.id = i.credit_note_for
            where i.issued_at >= :from and i.issued_at < :to
            """;

    private static Map<String, Long> sums(JdbcClient jdbc, YearMonth month, String kinds) {
        var range = range(month);
        return jdbc.sql("""
                        select coalesce(sum(taxable_paise), 0) as taxable, coalesce(sum(cgst_paise), 0) as cgst,
                               coalesce(sum(sgst_paise), 0) as sgst, coalesce(sum(igst_paise), 0) as igst,
                               coalesce(sum(total_paise), 0) as total, count(*) as documents
                        from billing.invoices
                        where issued_at >= :from and issued_at < :to and kind in (%s)
                        """.formatted(kinds))
                .param("from", range[0])
                .param("to", range[1])
                .query((rs, n) -> {
                    var totals = new LinkedHashMap<String, Long>();
                    for (var name : new String[] {"taxable", "cgst", "sgst", "igst", "total", "documents"}) {
                        totals.put(name, rs.getLong(name));
                    }
                    return (Map<String, Long>) totals;
                })
                .single();
    }

    private static final String DOCUMENTS = "'tax_invoice', 'receipt'";
    private static final String NOTES = "'credit_note'";

    /** A CSV of invoices or credit notes in the month, one a row. */
    private static TaxExport register(JdbcClient jdbc, String name, String description, String kinds, boolean notes) {
        return new TaxExport() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String fileName(YearMonth month) {
                return name + "-" + month + ".csv";
            }

            @Override
            public String contentType() {
                return "text/csv";
            }

            @Override
            public boolean inPack() {
                return true;
            }

            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var range = range(month);
                BillingTaxExports.write(
                        out,
                        Csv.line(
                                "number",
                                notes ? "against" : "kind",
                                "issued_at",
                                "account",
                                "buyer_gstin",
                                "place_of_supply",
                                "sac",
                                "rate_percent",
                                "taxable_paise",
                                "cgst_paise",
                                "sgst_paise",
                                "igst_paise",
                                "total_paise"));
                var rows = jdbc.sql(INVOICES + " and i.kind in (" + kinds + ") order by i.issued_at, i.number")
                        .param("from", range[0])
                        .param("to", range[1])
                        .query((rs, n) -> Csv.line(
                                rs.getString("number"),
                                notes ? rs.getString("against") : rs.getString("kind"),
                                rs.getTimestamp("issued_at").toInstant().toString(),
                                rs.getString("user_id"),
                                rs.getString("buyer_gstin"),
                                rs.getString("place"),
                                rs.getString("sac"),
                                rs.getLong("rate"),
                                rs.getLong("taxable_paise"),
                                rs.getLong("cgst_paise"),
                                rs.getLong("sgst_paise"),
                                rs.getLong("igst_paise"),
                                rs.getLong("total_paise")))
                        .list();
                for (var row : rows) {
                    BillingTaxExports.write(out, row);
                }
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                return sums(jdbc, month, kinds);
            }
        };
    }

    @Bean
    TaxExport invoiceRegister(JdbcClient jdbc) {
        return register(
                jdbc, "invoice-register", "Every tax invoice and receipt issued in the month", DOCUMENTS, false);
    }

    @Bean
    TaxExport creditNotes(JdbcClient jdbc) {
        return register(jdbc, "credit-notes", "Every credit note issued in the month", NOTES, true);
    }

    /**
     * GSTR-1's data for the GST portal's offline tool (CA(confirm) on the format): B2B invoice by invoice with the
     * buyer's GSTIN; B2C small, summed by place of supply and rate, intra-state or inter-state; and credit notes to
     * registered buyers.
     */
    @Bean
    TaxExport gstr1(JdbcClient jdbc, Gst gst) {
        return new TaxExport() {
            @Override
            public String name() {
                return "gstr1";
            }

            @Override
            public String description() {
                return "GSTR-1's data as JSON for the GST portal's offline tool (CA(confirm) on the format)";
            }

            @Override
            public String fileName(YearMonth month) {
                return "gstr1-" + month + ".json";
            }

            @Override
            public String contentType() {
                return "application/json";
            }

            @Override
            public boolean inPack() {
                return true;
            }

            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var range = range(month);
                var settings = gst.settings();
                var root = JSON.createObjectNode();
                root.put("gstin", settings.gstin());
                root.put("fp", "%02d%04d".formatted(month.getMonthValue(), month.getYear()));
                ArrayNode b2b = root.putArray("b2b");
                ArrayNode cdnr = root.putArray("cdnr");
                var b2cs = new TreeMap<String, long[]>();
                var byBuyer = new LinkedHashMap<String, ArrayNode>();
                var notesByBuyer = new LinkedHashMap<String, ArrayNode>();
                jdbc.sql(INVOICES + " order by i.issued_at, i.number")
                        .param("from", range[0])
                        .param("to", range[1])
                        .query(rs -> {
                            var gstin = rs.getString("buyer_gstin");
                            var place = rs.getString("place");
                            long rate = rs.getLong("rate");
                            boolean note = rs.getString("kind").equals("credit_note");
                            if (gstin == null || gstin.isBlank()) {
                                if (note) {
                                    // A credit note to an unregistered buyer lowers the B2C figure it came from.
                                    add(b2cs, place, rate, rs, -1, settings.stateCode());
                                } else {
                                    add(b2cs, place, rate, rs, 1, settings.stateCode());
                                }
                                return;
                            }
                            var document = JSON.createObjectNode();
                            document.put(note ? "nt_num" : "inum", rs.getString("number"));
                            document.put(
                                    note ? "nt_dt" : "idt",
                                    rs.getTimestamp("issued_at")
                                            .toInstant()
                                            .atZone(Periods.INDIA)
                                            .toLocalDate()
                                            .format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")));
                            document.put("val", rupees(rs.getLong("total_paise")));
                            document.put("pos", place);
                            if (note) {
                                document.put("ntty", "C");
                            } else {
                                document.put("rchrg", "N");
                                document.put("inv_typ", "R");
                            }
                            var item = document.putArray("itms").addObject();
                            item.put("num", 1);
                            var detail = item.putObject("itm_det");
                            detail.put("txval", rupees(rs.getLong("taxable_paise")));
                            detail.put("rt", rate);
                            detail.put("iamt", rupees(rs.getLong("igst_paise")));
                            detail.put("camt", rupees(rs.getLong("cgst_paise")));
                            detail.put("samt", rupees(rs.getLong("sgst_paise")));
                            detail.put("csamt", rupees(0));
                            (note ? notesByBuyer : byBuyer)
                                    .computeIfAbsent(gstin, key -> JSON.createArrayNode())
                                    .add(document);
                        });
                byBuyer.forEach((gstin, invoices) -> {
                    var buyer = b2b.addObject();
                    buyer.put("ctin", gstin);
                    buyer.set("inv", invoices);
                });
                notesByBuyer.forEach((gstin, notes) -> {
                    var buyer = cdnr.addObject();
                    buyer.put("ctin", gstin);
                    buyer.set("nt", notes);
                });
                ArrayNode small = root.putArray("b2cs");
                b2cs.forEach((key, sums) -> {
                    var parts = key.split("\\|");
                    ObjectNode row = small.addObject();
                    row.put("sply_ty", parts[0]);
                    row.put("pos", parts[1]);
                    row.put("typ", "OE");
                    row.put("rt", Long.parseLong(parts[2]));
                    row.put("txval", rupees(sums[0]));
                    row.put("iamt", rupees(sums[1]));
                    row.put("camt", rupees(sums[2]));
                    row.put("samt", rupees(sums[3]));
                    row.put("csamt", rupees(0));
                });
                out.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                var documents = sums(jdbc, month, DOCUMENTS);
                var notes = sums(jdbc, month, NOTES);
                var net = new LinkedHashMap<String, Long>();
                for (var name : new String[] {"taxable", "cgst", "sgst", "igst"}) {
                    net.put(name, documents.get(name) - notes.get(name));
                }
                return net;
            }
        };
    }

    /** A B2C row's sums, by supply type, place and rate: taxable, IGST, CGST, SGST. */
    private static void add(
            TreeMap<String, long[]> b2cs, String place, long rate, java.sql.ResultSet rs, int sign, String ours)
            throws java.sql.SQLException {
        var type = ours.equals(place) ? "INTRA" : "INTER";
        var sums = b2cs.computeIfAbsent(type + "|" + place + "|" + rate, key -> new long[4]);
        sums[0] += sign * rs.getLong("taxable_paise");
        sums[1] += sign * rs.getLong("igst_paise");
        sums[2] += sign * rs.getLong("cgst_paise");
        sums[3] += sign * rs.getLong("sgst_paise");
    }

    /** The month's GST: what the invoices and credit notes say, against what the ledger owes, and any difference. */
    @Bean
    TaxExport gstSummary(JdbcClient jdbc) {
        return new TaxExport() {
            @Override
            public String name() {
                return "gst-summary";
            }

            @Override
            public String description() {
                return "The month's GST from its documents, against the ledger's tax accounts";
            }

            @Override
            public String fileName(YearMonth month) {
                return "gst-summary-" + month + ".csv";
            }

            @Override
            public String contentType() {
                return "text/csv";
            }

            @Override
            public boolean inPack() {
                return true;
            }

            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var totals = totals(month);
                BillingTaxExports.write(out, Csv.line("figure", "paise"));
                for (var total : totals.entrySet()) {
                    BillingTaxExports.write(out, Csv.line(total.getKey(), total.getValue()));
                }
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                var documents = sums(jdbc, month, DOCUMENTS);
                var notes = sums(jdbc, month, NOTES);
                var range = range(month);
                var owed = new LinkedHashMap<String, Long>();
                jdbc.sql("""
                                select account,
                                       coalesce(sum(case direction when 'credit' then amount_paise
                                                                   else -amount_paise end), 0) as paise
                                from billing.ledger_entries
                                where account in ('tax_cgst', 'tax_sgst', 'tax_igst')
                                  and created_at >= :from and created_at < :to
                                group by account
                                """).param("from", range[0]).param("to", range[1]).query(rs -> {
                    owed.put(rs.getString("account"), rs.getLong("paise"));
                });
                var totals = new LinkedHashMap<String, Long>();
                for (var tax : new String[] {"cgst", "sgst", "igst"}) {
                    long documented = documents.get(tax) - notes.get(tax);
                    long inLedger = owed.getOrDefault("tax_" + tax, 0L);
                    totals.put(tax + "_documents", documented);
                    totals.put(tax + "_ledger", inLedger);
                    totals.put(tax + "_difference", documented - inLedger);
                }
                totals.put("taxable_documents", documents.get("taxable") - notes.get("taxable"));
                return totals;
            }
        };
    }

    /** A file of the CA pack only: written from the ledger, its name the month's. */
    private abstract static class PackFile implements TaxExport {

        private final String name;
        private final String description;

        PackFile(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String fileName(YearMonth month) {
            return name + "-" + month + ".csv";
        }

        @Override
        public String contentType() {
            return "text/csv";
        }

        @Override
        public boolean inPack() {
            return true;
        }
    }

    /** The trial balance at the month's end: each account's net, in the debit or the credit column. */
    @Bean
    TaxExport trialBalanceFile(LedgerExplorer explorer) {
        return new PackFile("trial-balance", "The trial balance at the month's end") {
            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var trial = explorer.trialBalance(range(month)[1].toInstant().minusNanos(1_000));
                BillingTaxExports.write(out, Csv.line("account", "type", "debit_paise", "credit_paise"));
                for (var balance : trial.accounts()) {
                    long net = balance.debitsPaise() - balance.creditsPaise();
                    BillingTaxExports.write(
                            out,
                            Csv.line(
                                    balance.account().dbValue,
                                    balance.account().type.dbValue(),
                                    Math.max(net, 0),
                                    Math.max(-net, 0)));
                }
                BillingTaxExports.write(out, Csv.line("total", "", trial.debitsPaise(), trial.creditsPaise()));
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                var trial = explorer.trialBalance(range(month)[1].toInstant().minusNanos(1_000));
                return Map.of("debits", trial.debitsPaise(), "credits", trial.creditsPaise());
            }
        };
    }

    /** The month's journal: every entry dated in it, by transaction. */
    @Bean
    TaxExport journalFile(JdbcClient jdbc) {
        return new PackFile("journal", "Every ledger entry dated in the month, by transaction") {
            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var range = range(month);
                BillingTaxExports.write(
                        out,
                        Csv.line(
                                "created_at",
                                "txn_id",
                                "account",
                                "owner_id",
                                "direction",
                                "amount_paise",
                                "ref_type",
                                "ref_id"));
                var rows = jdbc.sql("""
                                select * from billing.ledger_entries where created_at >= :from and created_at < :to
                                order by created_at, txn_id, direction desc, account
                                """)
                        .param("from", range[0])
                        .param("to", range[1])
                        .query((rs, n) -> Csv.line(
                                rs.getTimestamp("created_at").toInstant().toString(),
                                rs.getString("txn_id"),
                                rs.getString("account"),
                                rs.getString("owner_id"),
                                rs.getString("direction"),
                                rs.getLong("amount_paise"),
                                rs.getString("ref_type"),
                                rs.getString("ref_id")))
                        .list();
                for (var row : rows) {
                    BillingTaxExports.write(out, row);
                }
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                var range = range(month);
                return jdbc.sql("""
                                select coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
                                       coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
                                from billing.ledger_entries where created_at >= :from and created_at < :to
                                """)
                        .param("from", range[0])
                        .param("to", range[1])
                        .query((rs, n) -> Map.of("debits", rs.getLong("debits"), "credits", rs.getLong("credits")))
                        .single();
            }
        };
    }

    /** The month's settlements and every item that didn't match, with how it was resolved. */
    @Bean
    TaxExport settlementReconciliationFile(JdbcClient jdbc) {
        return new PackFile("settlement-reconciliation", "Razorpay's settlements of the month, and what didn't match") {
            @Override
            public void write(YearMonth month, OutputStream out) throws IOException {
                var range = range(month);
                BillingTaxExports.write(
                        out,
                        Csv.line(
                                "settlement",
                                "settled_at",
                                "utr",
                                "settled_paise",
                                "fees_paise",
                                "tax_paise",
                                "state",
                                "item",
                                "type",
                                "match",
                                "item_state",
                                "resolution",
                                "reason"));
                var rows = jdbc.sql("""
                                select s.id, s.settled_at, s.utr, s.amount_paise, s.fees_paise, s.tax_paise, s.state,
                                       i.entity_id, i.type, i.match, i.state as item_state, i.resolution, i.reason
                                from billing.settlements s
                                left join billing.settlement_items i on i.settlement_id = s.id and i.match <> 'matched'
                                where s.settled_at >= :from and s.settled_at < :to
                                order by s.settled_at, s.id, i.entity_id
                                """)
                        .param("from", range[0])
                        .param("to", range[1])
                        .query((rs, n) -> Csv.line(
                                rs.getString("id"),
                                rs.getTimestamp("settled_at").toInstant().toString(),
                                rs.getString("utr"),
                                rs.getLong("amount_paise"),
                                rs.getLong("fees_paise"),
                                rs.getLong("tax_paise"),
                                rs.getString("state"),
                                rs.getString("entity_id"),
                                rs.getString("type"),
                                rs.getString("match"),
                                rs.getString("item_state"),
                                rs.getString("resolution"),
                                rs.getString("reason")))
                        .list();
                for (var row : rows) {
                    BillingTaxExports.write(out, row);
                }
            }

            @Override
            public Map<String, Long> totals(YearMonth month) {
                var range = range(month);
                return jdbc.sql("""
                                select coalesce(sum(amount_paise), 0) as settled, coalesce(sum(fees_paise), 0) as fees,
                                       coalesce(sum(tax_paise), 0) as tax
                                from billing.settlements where settled_at >= :from and settled_at < :to
                                """)
                        .param("from", range[0])
                        .param("to", range[1])
                        .query((rs, n) -> Map.of(
                                "settled", rs.getLong("settled"), "fees", rs.getLong("fees"), "tax", rs.getLong("tax")))
                        .single();
            }
        };
    }
}
