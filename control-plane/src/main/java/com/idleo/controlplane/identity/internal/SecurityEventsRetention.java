package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The security log is deleted after {@code legal.retention.security-events} (ADR 0037), oldest first, in batches. */
@Component
class SecurityEventsRetention implements RetentionRule {

    private static final int BATCH = 5_000;

    private final JdbcClient jdbc;
    private final LegalSettings legal;

    SecurityEventsRetention(JdbcClient jdbc, LegalSettings legal) {
        this.jdbc = jdbc;
        this.legal = legal;
    }

    @Override
    public String name() {
        return "security-events";
    }

    @Override
    public long apply(Instant now) {
        var before = Timestamp.from(now.minus(legal.retention().securityEvents()));
        long deleted = 0;
        int batch;
        do {
            batch = jdbc.sql("""
                            delete from identity.security_events where id in (
                                select id from identity.security_events where at < :before limit :batch)
                            """).param("before", before).param("batch", BATCH).update();
            deleted += batch;
        } while (batch == BATCH);
        return deleted;
    }
}
