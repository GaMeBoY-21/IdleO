package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.billing.TaxExport;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CA's files (ADR 0055): any one of them, or the month's pack, one zip holding every file the pack takes (the trial
 * balance, the journal, the invoice register, credit notes, GSTR-1's data, the GST summary, the payouts, the month's TDS
 * and the settlement reconciliation) and a manifest with each file's SHA-256 and what it adds up to. Each is asked for as
 * an approved export with a factor; the pack's manifest hash goes into the audit log, and the pack is kept as made (but
 * not its files, which hold whole PANs).
 */
@Component
class CaPacks {

    static final String PACK = "ca-pack";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<TaxExport> exports;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    CaPacks(
            List<TaxExport> exports,
            JdbcClient jdbc,
            AuditTrail audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.exports = List.copyOf(exports);
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    List<TaxExport> exports() {
        return exports;
    }

    Optional<TaxExport> export(String name) {
        return exports.stream().filter(export -> export.name().equals(name)).findFirst();
    }

    /** One file, audited with what it adds up to. */
    Approvals.File file(TaxExport export, YearMonth month, String reason, AuditTrail.Actor actor) {
        // The file is made when it's fetched, after the approval ran: its audit row has a transaction of its own.
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                actor,
                "tax.export",
                "tax_export",
                export.name() + ":" + month,
                reason,
                null,
                new LinkedHashMap<String, Object>(export.totals(month)))));
        return new Approvals.File(export.contentType(), export.fileName(month), out -> export.write(month, out));
    }

    /** One file of a pack: its name, its bytes and their SHA-256. */
    record Part(String export, String fileName, byte[] bytes, String sha256, Map<String, Long> totals) {}

    /** The month's pack: each file made now, its hash taken; the manifest last, its hash audited and kept. */
    Approvals.File pack(UUID approvalId, YearMonth month, String reason, AuditTrail.Actor actor) {
        var parts = parts(month);
        var manifest = manifest(month, parts);
        var manifestSha = sha256(manifest);
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into billing.ca_packs (id, month, files, manifest_sha256, actor, approval_id, created_at)
                            values (:id, :month, :files, :sha, :actor, :approval, :now)
                            on conflict (approval_id) do nothing
                            """)
                    .param("id", UUID.nameUUIDFromBytes(("ca-pack:" + approvalId).getBytes(StandardCharsets.UTF_8)))
                    .param("month", month.atDay(1))
                    .param("files", parts.size())
                    .param("sha", manifestSha)
                    .param("actor", actor.label())
                    .param("approval", approvalId)
                    .param("now", Timestamp.from(clock.instant()))
                    .update();
            var hashes = new LinkedHashMap<String, Object>();
            hashes.put("manifestSha256", manifestSha);
            for (var part : parts) {
                hashes.put(part.fileName(), part.sha256());
            }
            audit.record(new AuditTrail.Entry(actor, "tax.ca_pack", "ca_pack", month.toString(), reason, null, hashes));
        });
        return new Approvals.File("application/zip", "idleo-ca-pack-" + month + ".zip", out -> {
            var zip = new ZipOutputStream(out);
            for (var part : parts) {
                zip.putNextEntry(new ZipEntry(part.fileName()));
                zip.write(part.bytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            zip.finish();
        });
    }

    /** Every file the pack takes, made for the month, with its hash. */
    List<Part> parts(YearMonth month) {
        var parts = new ArrayList<Part>();
        for (var export : exports) {
            if (!export.inPack()) {
                continue;
            }
            var bytes = new ByteArrayOutputStream();
            try {
                export.write(month, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            var made = bytes.toByteArray();
            parts.add(new Part(export.name(), export.fileName(month), made, sha256(made), export.totals(month)));
        }
        return parts;
    }

    /** The manifest: the month and each file, its SHA-256, its size and what it adds up to. */
    static byte[] manifest(YearMonth month, List<Part> parts) {
        var root = JSON.createObjectNode();
        root.put("month", month.toString());
        var files = root.putArray("files");
        for (var part : parts) {
            var file = files.addObject();
            file.put("name", part.fileName());
            file.put("export", part.export());
            file.put("bytes", part.bytes().length);
            file.put("sha256", part.sha256());
            var totals = file.putObject("totals");
            part.totals().forEach(totals::put);
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a pack has been made for the month. */
    boolean made(YearMonth month) {
        return jdbc.sql("select count(*) from billing.ca_packs where month = :month")
                        .param("month", month.atDay(1))
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** The packs made, newest first: the month, how many files, the manifest's hash, who and when. */
    record Made(String month, int files, String manifestSha256, String actor, java.time.Instant at) {}

    List<Made> latest(int limit) {
        return jdbc.sql("select * from billing.ca_packs order by created_at desc, id desc limit :limit")
                .param("limit", limit)
                .query((rs, n) -> new Made(
                        YearMonth.from(rs.getObject("month", java.time.LocalDate.class))
                                .toString(),
                        rs.getInt("files"),
                        rs.getString("manifest_sha256"),
                        rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
