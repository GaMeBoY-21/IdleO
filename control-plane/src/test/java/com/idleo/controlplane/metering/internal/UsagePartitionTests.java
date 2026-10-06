package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** usage_minutes is partitioned by month, always three months ahead, with no catch-all partition. */
@ControlPlaneTest
class UsagePartitionTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsagePartitions partitions;

    private boolean hasPartition(YearMonth month) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass(?) is not null",
                Boolean.class,
                "metering.usage_minutes_%04d_%02d".formatted(month.getYear(), month.getMonthValue())));
    }

    @Test
    void thisMonthAndTheNextThreeExistAndThereIsNoDefaultPartition() {
        var month = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= 3; i++) {
            assertThat(hasPartition(month.plusMonths(i)))
                    .as(month.plusMonths(i).toString())
                    .isTrue();
        }
        assertThat(jdbc.queryForObject("""
                        select count(*) from pg_inherits i join pg_class c on c.oid = i.inhrelid
                        where i.inhparent = 'metering.usage_minutes'::regclass
                          and pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT'
                        """, Integer.class)).isZero();
    }

    @Test
    void theDailyJobCreatesPartitionsThreeMonthsAheadAndIsSafeToRepeat() {
        var later = YearMonth.now(ZoneOffset.UTC).plusMonths(8);
        var at = later.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();
        var minuteAt = Timestamp.from(
                later.plusMonths(3).atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThatThrownBy(() -> jdbc.update(
                        "insert into metering.usage_minutes (session_id, minute_index, minute_at) values (?, 0, ?)",
                        UUID.randomUUID(),
                        minuteAt))
                .as("a month without a partition fails loudly")
                .hasMessageContaining("no partition");

        partitions.ensure(at);
        partitions.ensure(at);

        for (int i = 0; i <= 3; i++) {
            assertThat(hasPartition(later.plusMonths(i))).isTrue();
        }
        jdbc.update(
                "insert into metering.usage_minutes (session_id, minute_index, minute_at) values (?, 0, ?)",
                UUID.randomUUID(),
                minuteAt);
    }

    @Test
    void theJobAtTheEndOfAMonthStillReachesThreeMonthsAhead() {
        var lastSecond =
                YearMonth.now(ZoneOffset.UTC).plusMonths(20).atEndOfMonth().atTime(23, 59, 59);
        partitions.ensure(lastSecond.toInstant(ZoneOffset.UTC));
        assertThat(hasPartition(YearMonth.from(lastSecond).plusMonths(3))).isTrue();
    }
}
