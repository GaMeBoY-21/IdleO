package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The control plane's lease record as coverage (ADR 0017), the second witness for every minute. Each send of a
 * StartSession or a LeaseRenew covers from its send time to that time plus the lease. A send inside the current
 * stretch extends it; one after it ended, a late renewal, starts a new stretch, so the gap stays uncovered.
 */
@Component
class LeaseBook {

    private final JdbcClient jdbc;
    private final IdGenerator ids;

    LeaseBook(JdbcClient jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /** Records a lease sent at {@code sentAt} for {@code ttl}. The caller holds the session's row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(UUID sessionId, int attempt, Instant sentAt, Duration ttl) {
        var from = sentAt.truncatedTo(ChronoUnit.MICROS);
        var until = from.plus(ttl);
        int extended = jdbc.sql("""
                        update sessions.lease_coverage set covered_until = greatest(covered_until, :until)
                        where id = (select id from sessions.lease_coverage
                                    where session_id = :sessionId and attempt = :attempt
                                    order by covered_until desc limit 1)
                          and covered_from <= :from and covered_until >= :from
                        """)
                .param("until", Timestamp.from(until))
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("from", Timestamp.from(from))
                .update();
        if (extended == 0) {
            jdbc.sql("""
                            insert into sessions.lease_coverage (id, session_id, attempt, covered_from, covered_until)
                            values (:id, :sessionId, :attempt, :from, :until)
                            """)
                    .param("id", ids.newId())
                    .param("sessionId", sessionId)
                    .param("attempt", attempt)
                    .param("from", Timestamp.from(from))
                    .param("until", Timestamp.from(until))
                    .update();
        }
    }

    boolean covered(UUID sessionId, int attempt, Instant from, Instant until) {
        return jdbc.sql("""
                        select exists (select 1 from sessions.lease_coverage
                                       where session_id = :sessionId and attempt = :attempt
                                         and covered_from <= :from and covered_until >= :until)
                        """)
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("from", Timestamp.from(from))
                .param("until", Timestamp.from(until))
                .query(Boolean.class)
                .single();
    }
}
