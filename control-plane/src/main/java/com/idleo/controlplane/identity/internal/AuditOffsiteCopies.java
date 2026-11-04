package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.OneAtATime;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

/**
 * An off-site copy of the audit log (ADR 0051): each finished India day's rows, and the chain head after them, as files
 * in a store the control plane can only add to. A file is written once, never replaced: asking for a key that exists
 * finds the same bytes or says so. Days missed are caught up in order, so the files always join, and a day with no rows
 * still has its head. A day without a copy alerts (AuditCopyMissing, docs/runbooks/alerts.md).
 */
@Component
@EnableConfigurationProperties(AuditProperties.class)
class AuditOffsiteCopies {

    private static final Logger log = LoggerFactory.getLogger(AuditOffsiteCopies.class);
    private static final String JOB = "audit-offsite-copy";
    private static final String SHA256_METADATA = "sha256";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OneAtATime oneAtATime;
    private final JsonMapper json;
    private final ZoneId zone;
    private final String bucket;
    private final String prefix;
    private final @Nullable S3Client s3;
    private final Instant watchingSince;

    private volatile @Nullable Instant newestDayEnd;

    AuditOffsiteCopies(
            JdbcClient jdbc,
            Clock clock,
            OneAtATime oneAtATime,
            JsonMapper json,
            AuditProperties properties,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.oneAtATime = oneAtATime;
        this.json = json;
        this.zone = ZoneId.of(properties.zone());
        var offsite = properties.offsite();
        this.bucket = offsite.bucket();
        this.prefix = offsite.prefix();
        this.watchingSince = Instant.now(clock);
        if (!offsite.configured()) {
            this.s3 = null;
            return;
        }
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(offsite.endpoint()))
                .region(Region.of(offsite.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(offsite.accessKeyId(), offsite.secretAccessKey())))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        Gauge.builder("audit.offsite.age", this, AuditOffsiteCopies::ageSeconds)
                .description("Seconds since the end of the newest India day with an off-site copy of the audit log")
                .baseUnit("seconds")
                .register(registry);
    }

    /** One day's copy, as recorded. */
    record Copy(LocalDate day, long rows, long lastSeq, String headHash, String objectKey, Instant writtenAt) {}

    private double ageSeconds() {
        var end = newestDayEnd;
        return Duration.between(end == null ? watchingSince : end, Instant.now(clock))
                        .toMillis()
                / 1000.0;
    }

    /** What the gauge says, as the database has it: per instance, once a minute. */
    @Scheduled(fixedDelayString = "${identity.audit.refresh-every}", initialDelayString = "PT15S")
    void refresh() {
        newestDayEnd = newest().map(
                        copy -> copy.day().plusDays(1).atStartOfDay(zone).toInstant())
                .orElse(null);
    }

    /** Every 15 minutes, on one instance; there's work only once a day has ended. */
    @Scheduled(fixedDelayString = "${identity.audit.copy-every}", initialDelayString = "PT1M")
    void scheduled() {
        if (s3 != null) {
            oneAtATime.run(JOB, this::copyFinishedDays);
        }
    }

    /** Writes every finished day not yet copied, oldest first. */
    void copyFinishedDays() {
        var today = LocalDate.ofInstant(Instant.now(clock), zone);
        var last = newest();
        var day = last.map(copy -> copy.day().plusDays(1)).orElseGet(this::firstDay);
        for (; day.isBefore(today); day = day.plusDays(1)) {
            copy(day, last.map(Copy::lastSeq).orElse(0L));
            last = newest();
        }
    }

    private LocalDate firstDay() {
        return jdbc.sql("select min(created_at) from identity.audit_log")
                .query(Timestamp.class)
                .optional()
                .map(first -> LocalDate.ofInstant(first.toInstant(), zone))
                .orElseGet(() -> LocalDate.ofInstant(Instant.now(clock), zone).minusDays(1));
    }

    /** The rows after {@code afterSeq} up to the first one past this day's end, in the chain's order. */
    private void copy(LocalDate day, long afterSeq) {
        var end = Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());
        var lines = new ByteArrayOutputStream();
        var count = new long[1];
        var seqs = new ArrayList<Long>();
        var hashes = new ArrayList<byte[]>();
        var firstPastEnd = jdbc.sql("select min(seq) from identity.audit_log where seq > :after and created_at >= :end")
                .param("after", afterSeq)
                .param("end", end)
                .query(Long.class)
                .optional()
                .orElse(null);
        jdbc.sql("""
                        select seq, id, actor_kind, actor_user_id, actor_name, action, target_type, target_id, reason,
                               before::text as before, after::text as after,
                               (extract(epoch from created_at) * 1000000)::bigint as created_at_micros,
                               request_id, admin_session_id, host(ip) as ip, user_agent, approval_id, prev_hash, hash
                        from identity.audit_log
                        where seq > :after and (cast(:until as bigint) is null or seq < cast(:until as bigint))
                        order by seq
                        """).param("after", afterSeq).param("until", firstPastEnd).query(rs -> {
            var row = new LinkedHashMap<String, Object>();
            var meta = rs.getMetaData();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                var name = meta.getColumnLabel(column);
                var value = rs.getObject(column);
                row.put(
                        name,
                        value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes)
                                : value == null ? null : value.toString());
            }
            lines.writeBytes(json.writeValueAsBytes(row));
            lines.write('\n');
            count[0]++;
            seqs.add(rs.getLong("seq"));
            hashes.add(rs.getBytes("hash"));
        });
        byte[] headHash;
        long lastSeq;
        if (seqs.isEmpty()) {
            lastSeq = afterSeq;
            headHash = afterSeq == 0
                    ? new byte[32]
                    : jdbc.sql("select hash from identity.audit_log where seq = :seq")
                            .param("seq", afterSeq)
                            .query(byte[].class)
                            .single();
        } else {
            lastSeq = seqs.getLast();
            headHash = hashes.getLast();
        }
        var year = String.valueOf(day.getYear());
        var key = join(prefix, year, day + ".ndjson.gz");
        var rows = gzip(lines.toByteArray());
        var digest = sha256(rows);
        put(key, rows, "application/gzip", digest);
        Map<String, Object> head = new HashMap<>();
        head.put("day", day.toString());
        head.put("rows", count[0]);
        head.put("firstSeq", seqs.isEmpty() ? null : seqs.getFirst());
        head.put("lastSeq", lastSeq);
        head.put("headHash", HexFormat.of().formatHex(headHash));
        head.put("sha256", digest);
        var headFile = json.writeValueAsBytes(head);
        put(join(prefix, year, day + ".head.json"), headFile, "application/json", sha256(headFile));
        jdbc.sql("""
                        insert into identity.audit_offsite_copies
                            (day, rows_held, first_seq, last_seq, head_hash, object_key, sha256, written_at)
                        values (:day, :rows, :first, :last, :head, :key, :sha256, :now)
                        on conflict (day) do nothing
                        """)
                .param("day", day)
                .param("rows", count[0])
                .param("first", seqs.isEmpty() ? null : seqs.getFirst())
                .param("last", lastSeq)
                .param("head", headHash)
                .param("key", key)
                .param("sha256", digest)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        log.info("Copied {} audit rows of {} off site", count[0], day);
    }

    private void put(String key, byte[] bytes, String type, String digest) {
        var storage = java.util.Objects.requireNonNull(s3, "no off-site store");
        try {
            storage.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(type)
                            .ifNoneMatch("*")
                            .metadata(Map.of(SHA256_METADATA, digest))
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            if (e.statusCode() != 412) {
                throw e;
            }
            var found = storage.headObject(
                            HeadObjectRequest.builder().bucket(bucket).key(key).build())
                    .metadata()
                    .get(SHA256_METADATA);
            if (!digest.equals(found)) {
                throw new IllegalStateException("the off-site copy " + key + " differs from the log");
            }
        }
    }

    /** The newest copy, if there is one. */
    Optional<Copy> newest() {
        return jdbc.sql("""
                        select day, rows_held, last_seq, encode(head_hash, 'hex') as head_hash, object_key, written_at
                        from identity.audit_offsite_copies order by day desc limit 1
                        """)
                .query((rs, n) -> new Copy(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("rows_held"),
                        rs.getLong("last_seq"),
                        rs.getString("head_hash"),
                        rs.getString("object_key"),
                        rs.getTimestamp("written_at").toInstant()))
                .optional();
    }

    /** The last {@code limit} days' copies, newest first. */
    List<Copy> latest(int limit) {
        return jdbc.sql("""
                        select day, rows_held, last_seq, encode(head_hash, 'hex') as head_hash, object_key, written_at
                        from identity.audit_offsite_copies order by day desc limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Copy(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("rows_held"),
                        rs.getLong("last_seq"),
                        rs.getString("head_hash"),
                        rs.getString("object_key"),
                        rs.getTimestamp("written_at").toInstant()))
                .list();
    }

    private static String join(String... parts) {
        return String.join(
                "/",
                java.util.Arrays.stream(parts).filter(part -> !part.isBlank()).toList());
    }

    private static byte[] gzip(byte[] bytes) {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
