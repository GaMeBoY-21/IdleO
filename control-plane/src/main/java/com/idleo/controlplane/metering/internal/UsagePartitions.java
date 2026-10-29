package com.idleo.controlplane.metering.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code metering.usage_minutes} partitioned three months ahead, a month per partition in UTC (technical
 * design: partitioned by month). Runs daily; one instance at a time, under an advisory lock, and creating a
 * partition that exists changes nothing. The database function {@code metering.ensure_usage_partition} makes each,
 * as its owner, since the application's own user can't create tables (ADR 0045). There's no default partition, so a
 * minute outside every partition would fail loudly: this job is what keeps that from happening.
 */
@Component
class UsagePartitions {

    /** The advisory lock key: "usgparts" in ASCII. */
    static final long LOCK_KEY = 0x7573677061727473L;

    static final int MONTHS_AHEAD = 3;

    private static final Logger log = LoggerFactory.getLogger(UsagePartitions.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    UsagePartitions(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${metering.partitions.cron}", zone = "UTC")
    void daily() {
        try {
            ensure(Instant.now(clock));
        } catch (RuntimeException e) {
            log.error("Couldn't create usage partitions ahead; it runs again tomorrow", e);
        }
    }

    /** This month's partition and the next three, as of {@code now}. */
    void ensure(Instant now) {
        transactions.executeWithoutResult(tx -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return;
            }
            var month = YearMonth.from(now.atZone(ZoneOffset.UTC));
            for (int i = 0; i <= MONTHS_AHEAD; i++) {
                jdbc.sql("select metering.ensure_usage_partition(:month)")
                        .param("month", month.plusMonths(i).atDay(1))
                        .query((rs, row) -> row)
                        .list();
            }
        });
    }
}
