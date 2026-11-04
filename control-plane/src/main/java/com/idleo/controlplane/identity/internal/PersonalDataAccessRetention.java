package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The access log is deleted after {@code legal.retention.security-events} (ADR 0051), a year, like the security log. */
@Component
class PersonalDataAccessRetention implements RetentionRule {

    private final JdbcClient jdbc;
    private final LegalSettings legal;

    PersonalDataAccessRetention(JdbcClient jdbc, LegalSettings legal) {
        this.jdbc = jdbc;
        this.legal = legal;
    }

    @Override
    public String name() {
        return "personal-data-access";
    }

    @Override
    public long apply(Instant now) {
        return jdbc.sql("delete from identity.personal_data_access where at < :before")
                .param("before", Timestamp.from(now.minus(legal.retention().securityEvents())))
                .update();
    }
}
