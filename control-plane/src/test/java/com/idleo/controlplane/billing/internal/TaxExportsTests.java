package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.billing.TaxExport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CA's files (ADR 0055): every file adds up to what it says it does, and that ties to the ledger; the month's pack
 * holds every file with a manifest whose hashes are the files', made once by approval and kept as made.
 */
@ControlPlaneTest
class TaxExportsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<TaxExport> exports;

    @Autowired
    TransactionTemplate transactions;

    private final JsonMapper json = JsonMapper.builder().build();

    private YearMonth month() {
        return YearMonth.from(clock.instant().atZone(Periods.INDIA));
    }

    private TaxExport export(String name) {
        return exports.stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private String file(String name, YearMonth month) throws Exception {
        var out = new ByteArrayOutputStream();
        export(name).write(month, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** A CSV column's sum: the file's own figures. */
    private static long column(String csv, String header) {
        var lines = csv.split("\n");
        var headers = List.of(lines[0].split(","));
        int index = headers.indexOf(header);
        assertThat(index).as(header).isNotNegative();
        long sum = 0;
        for (int i = 1; i < lines.length; i++) {
            var cells = lines[i].split(",", -1);
            if (cells.length > index && cells[index].matches("-?\\d+") && !cells[0].equals("total")) {
                sum += Long.parseLong(cells[index]);
            }
        }
        return sum;
    }

    private long ledger(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    @Test
    void everyFileAddsUpToItsTotalsAndTheTotalsTieToTheLedger() throws Exception {
        var month = month();
        var from = Timestamp.from(month.atDay(1).atStartOfDay(Periods.INDIA).toInstant());
        var to = Timestamp.from(
                month.plusMonths(1).atDay(1).atStartOfDay(Periods.INDIA).toInstant());
        // In one snapshot: other tests post while this one reads.
        var snapshot = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.executeWithoutResult(status -> {
            try {
                // The invoice register and credit notes, and GSTR-1's data, against the ledger's GST owed.
                var register = export("invoice-register").totals(month);
                assertThat(column(file("invoice-register", month), "cgst_paise"))
                        .isEqualTo(register.get("cgst"));
                assertThat(column(file("invoice-register", month), "taxable_paise"))
                        .isEqualTo(register.get("taxable"));
                var notes = export("credit-notes").totals(month);
                assertThat(column(file("credit-notes", month), "igst_paise")).isEqualTo(notes.get("igst"));
                var gstr1 = export("gstr1").totals(month);
                assertThat(gstr1.get("cgst")).isEqualTo(register.get("cgst") - notes.get("cgst"));
                var summary = export("gst-summary").totals(month);
                for (var tax : List.of("cgst", "sgst", "igst")) {
                    assertThat(summary.get(tax + "_ledger"))
                            .isEqualTo(ledger(
                                    "select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)"
                                            + " from billing.ledger_entries where account = ? and created_at >= ? and created_at < ?",
                                    "tax_" + tax,
                                    from,
                                    to));
                    assertThat(summary.get(tax + "_documents")).isEqualTo(register.get(tax) - notes.get(tax));
                }

                // The journal: every entry of the month, and it balances.
                var journal = export("journal").totals(month);
                assertThat(journal.get("debits")).isEqualTo(journal.get("credits"));
                var csv = file("journal", month);
                assertThat(csv.split("\n").length - 1).isEqualTo((int) ledger(
                        "select count(*) from billing.ledger_entries where created_at >= ? and created_at < ?",
                        from,
                        to));

                // The trial balance at the month's end balances.
                var trial = export("trial-balance").totals(month);
                assertThat(trial.get("debits")).isEqualTo(trial.get("credits"));

                // The settlements: what they moved to the bank is the ledger's.
                var settled = export("settlement-reconciliation").totals(month);
                assertThat(settled.get("settled"))
                        .isEqualTo(ledger(
                                "select coalesce(sum(e.amount_paise), 0) from billing.ledger_entries e"
                                        + " join billing.settlements s on s.txn_id = e.txn_id"
                                        + " where e.account = 'bank' and s.settled_at >= ? and s.settled_at < ?",
                                from,
                                to));

                // TDS: what the month's payouts deducted is what tds_payable was credited for them.
                var tds = export("tds").totals(month);
                assertThat(column(file("tds", month), "tds_paise")).isEqualTo(tds.get("tds"));
                assertThat(tds.get("tds"))
                        .isEqualTo(ledger(
                                "select coalesce(sum(e.amount_paise), 0) from billing.ledger_entries e"
                                        + " join payouts.payouts p on p.id = e.ref_id"
                                        + " where e.ref_type = 'payout' and e.account = 'tds_payable' and e.direction = 'credit'"
                                        + " and p.created_at >= ? and p.created_at < ?"
                                        + " and p.status not in ('failed', 'rejected', 'cancelled', 'reversed')",
                                from,
                                to));
                var quarter = export("tds-26q").totals(month);
                assertThat(column(file("tds-26q", month), "tds_paise")).isEqualTo(quarter.get("tds"));
                assertThat(column(file("tds-16a", month), "tds_paise")).isEqualTo(quarter.get("tds"));
                var payouts = export("payouts").totals(month);
                assertThat(column(file("payouts", month), "net_paise")).isEqualTo(payouts.get("net"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void theMonthsPackHoldsEveryFileAndItsManifestsHashesAreTheFiles() throws Exception {
        var month = month();
        var finance = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        var listed = json.readTree(mvc.perform(finance.as(get("/admin/v1/tax/exports")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(listed.get("exports")).hasSize(exports.size());

        var asked = json.readTree(mvc.perform(finance.as(post("/admin/v1/tax/exports"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"export\":\"ca-pack\",\"month\":\"" + month + "\",\"reason\":\"the CA's pack\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
        TestAdmins.approve(mvc, jdbc, clock, asked.toString());
        var zip = mvc.perform(
                        finance.as(post("/admin/v1/approvals/" + asked.get("id").asString() + "/download")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        var files = new HashMap<String, byte[]>();
        try (var entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                files.put(entry.getName(), entries.readAllBytes());
            }
        }
        JsonNode manifest = json.readTree(files.get("manifest.json"));
        assertThat(manifest.get("month").asString()).isEqualTo(month.toString());
        long packed = exports.stream().filter(TaxExport::inPack).count();
        assertThat(manifest.get("files")).hasSize((int) packed);
        assertThat(files).hasSize((int) packed + 1);
        for (var file : manifest.get("files")) {
            var bytes = files.get(file.get("name").asString());
            assertThat(bytes).as(file.get("name").asString()).isNotNull();
            assertThat(CaPacks.sha256(bytes)).isEqualTo(file.get("sha256").asString());
            assertThat(bytes.length).isEqualTo(file.get("bytes").asInt());
        }
        var names = new java.util.ArrayList<String>();
        manifest.get("files").forEach(file -> names.add(file.get("export").asString()));
        assertThat(names)
                .contains(
                        "trial-balance",
                        "journal",
                        "invoice-register",
                        "credit-notes",
                        "gstr1",
                        "gst-summary",
                        "payouts",
                        "tds",
                        "settlement-reconciliation");

        // Kept as made, its manifest's hash in the audit log; the month's checklist counts it.
        var sha = CaPacks.sha256(files.get("manifest.json"));
        assertThat(ledger(
                        "select count(*) from billing.ca_packs where month = ? and manifest_sha256 = ?",
                        month.atDay(1),
                        sha))
                .isOne();
        assertThat(ledger(
                        "select count(*) from identity.audit_log where action = 'tax.ca_pack' and target_id = ?"
                                + " and after->>'manifestSha256' = ?",
                        month.toString(),
                        sha))
                .isOne();
    }
}
