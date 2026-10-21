package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.SecurityLog;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The only writer of {@code identity.security_events} (ADR 0037). */
@Component
class SecurityEvents implements SecurityLog {

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    SecurityEvents(JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void record(Kind kind, @Nullable UUID userId, @Nullable String detail) {
        jdbc.sql("""
                        insert into identity.security_events (id, user_id, kind, detail, address, at)
                        values (:id, :userId, :kind, :detail, cast(:address as inet), :at)
                        """)
                .param("id", ids.newId())
                .param("userId", userId)
                .param("kind", kind.code())
                .param("detail", detail)
                .param("address", address())
                .param("at", Timestamp.from(Instant.now(clock)))
                .update();
    }

    /** The current request's address, as the servlet container (behind the proxy's forwarded headers) has it. */
    private static @Nullable String address() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
