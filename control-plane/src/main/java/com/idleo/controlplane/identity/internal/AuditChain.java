package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.OneAtATime;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks the audit log's hash chain (ADR 0051), daily and when an admin asks: every row's hash is what its previous
 * hash and content make, each row follows the one before with no number missing, and the head is the last row. The
 * database does the walking, in one statement. A break pages (alert AuditChainBroken, docs/runbooks/alerts.md).
 */
@Component
class AuditChain {

    private static final Logger log = LoggerFactory.getLogger(AuditChain.class);
    private static final String JOB = "audit-chain-check";

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final OneAtATime oneAtATime;
    private final Instant watchingSince;

    private volatile @Nullable Check latest;

    AuditChain(JdbcClient jdbc, IdGenerator ids, Clock clock, OneAtATime oneAtATime, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.oneAtATime = oneAtATime;
        this.watchingSince = Instant.now(clock);
        Gauge.builder("audit.chain.broken", this, chain -> chain.broken() ? 1 : 0)
                .description("1 when the audit log's latest chain check found a break")
                .register(registry);
        Gauge.builder("audit.chain.check.age", this, AuditChain::checkAgeSeconds)
                .description("Seconds since the audit log's chain was last checked")
                .baseUnit("seconds")
                .register(registry);
    }

    /** One check: how many rows it read and how long it took; when broken, the first row that doesn't add up, and how. */
    record Check(
            UUID id,
            String trigger,
            long rows,
            long headSeq,
            boolean intact,
            @Nullable Long brokenSeq,
            @Nullable String detail,
            long tookMs,
            Instant checkedAt) {}

    private boolean broken() {
        var check = latest;
        return check != null && !check.intact();
    }

    private double checkAgeSeconds() {
        var check = latest;
        var since = check == null ? watchingSince : check.checkedAt();
        return Duration.between(since, Instant.now(clock)).toMillis() / 1000.0;
    }

    /** What the gauges say, as the database has it: per instance, once a minute. */
    @Scheduled(fixedDelayString = "${identity.audit.refresh-every}", initialDelayString = "PT10S")
    void refresh() {
        latest = newest().orElse(null);
    }

    /** Daily, on one instance. */
    @Scheduled(cron = "${identity.audit.check-cron}", zone = "${identity.audit.zone}")
    void daily() {
        oneAtATime.run(JOB, () -> {
            verify("daily");
        });
    }

    /** Walks the whole chain now and records the result. */
    Check verify(String trigger) {
        long started = System.nanoTime();
        var walked = jdbc.sql("""
                        with walked as (
                            select a.seq, a.hash, a.prev_hash,
                                   lag(a.seq) over (order by a.seq) as seq_before,
                                   lag(a.hash) over (order by a.seq) as hash_before,
                                   sha256(a.prev_hash || identity.audit_row_content(a)) as worked_out
                            from identity.audit_log a
                        ), found as (
                            select seq,
                                   case
                                       when hash <> worked_out then 'its hash is not what its content makes'
                                       when seq_before is null and seq <> 1 then 'the rows before it are missing'
                                       when seq_before is not null and seq <> seq_before + 1 then 'a row before it is missing'
                                       when seq_before is null and prev_hash <> decode(repeat('00', 32), 'hex')
                                           then 'the first row does not start the chain'
                                       when seq_before is not null and prev_hash <> hash_before
                                           then 'it does not follow the row before it'
                                   end as fault
                            from walked
                        )
                        select (select count(*) from walked) as rows_read,
                               (select seq from found where fault is not null order by seq limit 1) as broken_seq,
                               (select fault from found where fault is not null order by seq limit 1) as fault,
                               (select max(seq) from walked) as last_seq,
                               (select hash from walked order by seq desc limit 1) as last_hash,
                               h.seq as head_seq, h.hash as head_hash
                        from identity.audit_chain_head h
                        """)
                .query((rs, n) -> new Walked(
                        rs.getLong("rows_read"),
                        rs.getObject("broken_seq", Long.class),
                        rs.getString("fault"),
                        rs.getObject("last_seq", Long.class),
                        rs.getBytes("last_hash"),
                        rs.getLong("head_seq"),
                        rs.getBytes("head_hash")))
                .single();
        var fault = walked.fault();
        var brokenSeq = walked.brokenSeq();
        if (fault == null && !walked.endsAtHead()) {
            fault = "the newest rows are missing: the head is at " + walked.headSeq();
            brokenSeq = walked.lastSeq() == null ? Long.valueOf(0) : walked.lastSeq();
        }
        var check = new Check(
                ids.newId(),
                trigger,
                walked.rows(),
                walked.headSeq(),
                fault == null,
                brokenSeq,
                fault,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                Instant.now(clock));
        jdbc.sql("""
                        insert into identity.audit_chain_checks
                            (id, trigger, rows_read, head_seq, intact, broken_seq, detail, took_ms, checked_at)
                        values (:id, :trigger, :rows, :head, :intact, :broken, :detail, :took, :at)
                        """)
                .param("id", check.id())
                .param("trigger", trigger)
                .param("rows", check.rows())
                .param("head", check.headSeq())
                .param("intact", check.intact())
                .param("broken", check.brokenSeq())
                .param("detail", check.detail())
                .param("took", check.tookMs())
                .param("at", Timestamp.from(check.checkedAt()))
                .update();
        latest = check;
        if (check.intact()) {
            log.info("The audit chain is intact: {} rows in {} ms", check.rows(), check.tookMs());
        } else {
            log.error("The audit chain is broken at row {}: {}", check.brokenSeq(), check.detail());
        }
        return check;
    }

    private record Walked(
            long rows,
            @Nullable Long brokenSeq,
            @Nullable String fault,
            @Nullable Long lastSeq,
            byte @Nullable [] lastHash,
            long headSeq,
            byte[] headHash) {

        boolean endsAtHead() {
            if (lastSeq == null) {
                return headSeq == 0;
            }
            return lastSeq == headSeq && java.util.Arrays.equals(lastHash, headHash);
        }
    }

    /** The newest check, if there has been one. */
    Optional<Check> newest() {
        return jdbc.sql("select * from identity.audit_chain_checks order by checked_at desc, id desc limit 1")
                .query((rs, n) -> new Check(
                        rs.getObject("id", UUID.class),
                        rs.getString("trigger"),
                        rs.getLong("rows_read"),
                        rs.getLong("head_seq"),
                        rs.getBoolean("intact"),
                        rs.getObject("broken_seq", Long.class),
                        rs.getString("detail"),
                        rs.getLong("took_ms"),
                        rs.getTimestamp("checked_at").toInstant()))
                .optional();
    }
}
