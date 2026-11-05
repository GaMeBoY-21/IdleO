package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a host's page reads of its past (ADR 0052): its score a day and every self-test it ran. Its changes of status are
 * on the host itself. Whether an agent was online isn't kept over time; only when it was last seen.
 */
@RestController
@ConditionalOnWebApplication
class AdminHostHistoryController {

    private static final int DAYS_SHOWN = 90;
    private static final int TESTS_SHOWN = 20;

    private final JdbcClient jdbc;

    AdminHostHistoryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record AdminHostScoreDay(Instant day, double reliability, double bandwidth, double penalty) {}

    record AdminHostSelfTestRun(
            UUID id,
            String state,
            Instant requestedAt,
            @Nullable Instant finishedAt) {}

    record AdminHostHistory(List<AdminHostScoreDay> scores, List<AdminHostSelfTestRun> selfTests) {}

    @RequiresPermission(AdminPermission.HOSTS_READ)
    @GetMapping("/admin/v1/hosts/{id}/history")
    AdminHostHistory history(@PathVariable UUID id) {
        var scores = jdbc.sql("""
                        select day, reliability, bandwidth, penalty from hosts.host_score_history
                        where host_id = :id order by day desc limit :days
                        """)
                .param("id", id)
                .param("days", DAYS_SHOWN)
                .query((rs, n) -> new AdminHostScoreDay(
                        rs.getTimestamp("day").toInstant(),
                        rs.getDouble("reliability"),
                        rs.getDouble("bandwidth"),
                        rs.getDouble("penalty")))
                .list();
        var tests = jdbc.sql("""
                        select id, state, requested_at, finished_at from hosts.self_test_runs
                        where host_id = :id order by requested_at desc limit :tests
                        """)
                .param("id", id)
                .param("tests", TESTS_SHOWN)
                .query((rs, n) -> new AdminHostSelfTestRun(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("finished_at") == null
                                ? null
                                : rs.getTimestamp("finished_at").toInstant()))
                .list();
        return new AdminHostHistory(scores, tests);
    }
}
