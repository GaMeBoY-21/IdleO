package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.RetentionRule;
import com.idleo.controlplane.TestStorageConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A session's objects after it ends (ADR 0037): checkpoints go after 7 days, outputs
 * and logs after 30, each session once, while its rows stay as its money record. A session still running is never
 * touched.
 */
@ControlPlaneTest
class SessionFilesRetentionTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StorageProperties properties;

    @Autowired
    MutableClock clock;

    @Autowired
    List<RetentionRule> rules;

    private RetentionRule rule(String name) {
        return rules.stream()
                .filter(rule -> rule.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private UUID session(String state, Duration endedAgo) {
        var id = UUID.randomUUID();
        var ended = Timestamp.from(clock.instant().minus(endedAgo));
        jdbc.update(
                "insert into sessions.sessions (id, renter_id, type, state, attempt, template_id, image_digest,"
                        + " vram_needed_mb, max_price_paise_per_min, max_spend_paise, max_runtime_seconds,"
                        + " idempotency_key, request_hash, state_changed_at, version, created_at)"
                        + " values (?, ?, 'batch', ?, 2, 'echo-input', 'image@sha256:1', 1024, 20, 1000, 600, ?, 'h', ?,"
                        + " 0, ?)",
                id,
                UUID.randomUUID(),
                state,
                "files-" + id,
                ended,
                ended);
        try (var s3 = TestS3.root(properties)) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                var prefix = StorageLayout.attemptPrefix(id, attempt);
                TestS3.put(s3, prefix + "checkpoints/00000001/state.bin", "state");
                TestS3.put(s3, prefix + "outputs/result.json", "{}");
                TestS3.put(s3, prefix + "logs/00000000.log", "line");
            }
        }
        return id;
    }

    private List<String> objects(UUID session) {
        try (var s3 = TestS3.root(properties)) {
            return s3
                    .listObjectsV2(request -> request.bucket(TestStorageConfiguration.BUCKET)
                            .prefix(StorageLayout.sessionPrefix(session)))
                    .contents()
                    .stream()
                    .map(object -> object.key()
                            .substring(StorageLayout.sessionPrefix(session).length()))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void checkpointsGoAfterAWeekAndEverythingElseAfterAMonth() {
        var week = session("COMPLETED", Duration.ofDays(8));
        var month = session("FAILED", Duration.ofDays(31));
        var recent = session("COMPLETED", Duration.ofDays(2));
        var running = session("RUNNING", Duration.ofDays(40));

        assertThat(rule("checkpoints").apply(clock.instant())).isGreaterThanOrEqualTo(4);
        assertThat(objects(week))
                .containsExactly(
                        "attempt-1/logs/00000000.log",
                        "attempt-1/outputs/result.json",
                        "attempt-2/logs/00000000.log",
                        "attempt-2/outputs/result.json");
        assertThat(rule("checkpoints").apply(clock.instant())).isZero();

        assertThat(rule("outputs").apply(clock.instant())).isGreaterThanOrEqualTo(4);
        assertThat(objects(month)).isEmpty();
        assertThat(objects(week)).hasSize(4);
        assertThat(objects(recent)).hasSize(6);
        assertThat(objects(running)).hasSize(6);
        assertThat(rule("outputs").apply(clock.instant())).isZero();

        // The rows stay: the session is a money record.
        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.sessions where id in (?, ?) and checkpoints_deleted_at is not null",
                        Integer.class,
                        week,
                        month))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select files_deleted_at is null from sessions.sessions where id = ?", Boolean.class, week))
                .isTrue();
    }
}
