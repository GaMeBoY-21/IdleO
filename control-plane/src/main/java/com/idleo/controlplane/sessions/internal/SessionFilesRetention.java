package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A session's objects in storage after it ends (ADR 0037, technical design "Data stores"): its checkpoints after
 * {@code retention.periods.checkpoints} (7 days), then everything else of it, its outputs and logs, after {@code
 * retention.periods.outputs} (30 days). Each session once, a few hundred at a run, and the listing checked empty. The
 * session's rows stay: they're its money record.
 */
@Configuration(proxyBeanMethods = false)
class SessionFilesRetention {

    private static final int BATCH = 200;

    private record Ended(UUID id, int attempts) {}

    private static List<Ended> ended(JdbcClient jdbc, String column, Instant before) {
        return jdbc.sql("""
                        select id, attempt from sessions.sessions
                        where state in (:final) and state_changed_at < :before and %s is null
                        order by state_changed_at limit :batch
                        """.formatted(column))
                .param(
                        "final",
                        Arrays.stream(SessionState.values())
                                .filter(state -> state.isFinal)
                                .map(Enum::name)
                                .toList())
                .param("before", Timestamp.from(before))
                .param("batch", BATCH)
                .query((rs, n) -> new Ended(rs.getObject(1, UUID.class), rs.getInt(2)))
                .list();
    }

    private static void mark(JdbcClient jdbc, String column, UUID session, Instant now) {
        jdbc.sql("update sessions.sessions set %s = :now where id = :id".formatted(column))
                .param("now", Timestamp.from(now))
                .param("id", session)
                .update();
    }

    @Bean
    RetentionRule checkpointsRule(JdbcClient jdbc, SessionStorage storage, RetentionPeriods periods) {
        return RetentionRule.of("checkpoints", now -> {
            long deleted = 0;
            for (var session : ended(jdbc, "checkpoints_deleted_at", now.minus(periods.of("checkpoints")))) {
                for (int attempt = 1; attempt <= session.attempts(); attempt++) {
                    deleted += storage.deletePrefix(StorageLayout.checkpointsPrefix(session.id(), attempt));
                }
                jdbc.sql("delete from sessions.checkpoints where session_id = :id")
                        .param("id", session.id())
                        .update();
                mark(jdbc, "checkpoints_deleted_at", session.id(), now);
            }
            return deleted;
        });
    }

    @Bean
    RetentionRule outputsRule(JdbcClient jdbc, SessionStorage storage, RetentionPeriods periods) {
        return RetentionRule.of("outputs", now -> {
            long deleted = 0;
            for (var session : ended(jdbc, "files_deleted_at", now.minus(periods.of("outputs")))) {
                deleted += storage.deletePrefix(StorageLayout.sessionPrefix(session.id()));
                jdbc.sql("delete from sessions.checkpoints where session_id = :id")
                        .param("id", session.id())
                        .update();
                jdbc.sql("""
                                update sessions.sessions
                                set files_deleted_at = :now, checkpoints_deleted_at = coalesce(checkpoints_deleted_at, :now)
                                where id = :id
                                """)
                        .param("now", Timestamp.from(now))
                        .param("id", session.id())
                        .update();
            }
            return deleted;
        });
    }
}
