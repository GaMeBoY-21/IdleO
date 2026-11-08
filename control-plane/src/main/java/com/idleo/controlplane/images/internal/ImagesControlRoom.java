package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.ControlRoom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The scanner, for the live dashboard's health (ADR 0056): the oldest check still waiting. One waiting longer than a
 * check may take in all means no scanner is working the queue.
 */
@Component
class ImagesControlRoom implements ControlRoom.HealthSource {

    private final JdbcClient jdbc;
    private final ImageProperties properties;

    ImagesControlRoom(JdbcClient jdbc, ImageProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        if (!properties.enabled()) {
            return List.of(new ControlRoom.Check("scanner", true, "renters' own images are off"));
        }
        var oldest = jdbc.sql("select min(created_at) from images.images where state in ('queued', 'checking')")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant)
                .orElse(null);
        if (oldest == null) {
            return List.of(new ControlRoom.Check("scanner", true, "no check waiting"));
        }
        var waited = Duration.between(oldest, now);
        return List.of(new ControlRoom.Check(
                "scanner",
                waited.compareTo(properties.checkDeadline()) <= 0,
                "the oldest check has waited " + waited.toMinutes() + " minutes"));
    }
}
