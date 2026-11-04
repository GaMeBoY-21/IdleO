package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in metering (ADR 0051): a minute in the review queue. */
@Component
class MeteringAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    MeteringAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of("review_minute", this::minute);
    }

    private OptionalLong minute(Map<String, String> variables) {
        try {
            return jdbc.sql("""
                            select version from metering.review_minutes
                            where session_id = :session and minute_index = :minute
                            """)
                    .param("session", UUID.fromString(variables.getOrDefault("sessionId", "")))
                    .param("minute", Integer.parseInt(variables.getOrDefault("minuteIndex", "")))
                    .query(Long.class)
                    .optional()
                    .map(OptionalLong::of)
                    .orElseGet(OptionalLong::empty);
        } catch (IllegalArgumentException notARecord) {
            return OptionalLong.empty();
        }
    }
}
