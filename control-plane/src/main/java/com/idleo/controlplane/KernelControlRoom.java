package com.idleo.controlplane;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The shared kernel's checks for the live dashboard (ADR 0056): the database, Valkey, and the newest backup's age where
 * this control plane watches backups. In the cluster CloudNativePG keeps them, and DatabaseNoBaseBackup watches it.
 */
@Component
class KernelControlRoom implements ControlRoom.HealthSource {

    /** A backup older than this is late: the daily base backup, and a day's grace. */
    static final Duration BACKUP_LATE = Duration.ofHours(48);

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final BackupWatch backups;

    KernelControlRoom(JdbcClient jdbc, StringRedisTemplate redis, BackupWatch backups) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.backups = backups;
    }

    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        var checks = new ArrayList<ControlRoom.Check>();
        long started = System.nanoTime();
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            checks.add(new ControlRoom.Check(
                    "database",
                    true,
                    "answered in "
                            + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms"));
        } catch (RuntimeException e) {
            checks.add(new ControlRoom.Check("database", false, "didn't answer"));
        }
        try {
            var pong = redis.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            checks.add(new ControlRoom.Check("valkey", "PONG".equalsIgnoreCase(pong), "answered " + pong));
        } catch (RuntimeException e) {
            checks.add(new ControlRoom.Check(
                    "valkey", false, "didn't answer: presence is unknown and hints wait for the sweeps"));
        }
        checks.add(backups.fullAge()
                .map(age -> new ControlRoom.Check(
                        "backup age",
                        age.compareTo(BACKUP_LATE) < 0,
                        "the newest full backup is " + age.toHours() + " hours old"))
                .orElseGet(() -> new ControlRoom.Check(
                        "backup age",
                        true,
                        "kept by the cluster's CloudNativePG; its alerts watch it (DatabaseNoBaseBackup)")));
        return checks;
    }
}
