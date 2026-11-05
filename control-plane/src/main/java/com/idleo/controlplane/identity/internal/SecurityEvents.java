package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.SecurityLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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
                        insert into identity.security_events (id, user_id, kind, detail, address, device, at)
                        values (:id, :userId, :kind, :detail, cast(:address as inet), :device, :at)
                        """)
                .param("id", ids.newId())
                .param("userId", userId)
                .param("kind", kind.code())
                .param("detail", detail)
                .param("address", address())
                .param("device", device())
                .param("at", Timestamp.from(Instant.now(clock)))
                .update();
    }

    /** The browser it came from, as a hash: what finds a same device (ADR 0052), and says nothing else about it. */
    private static @Nullable String device() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var browser = attributes.getRequest().getHeader("User-Agent");
            if (browser != null && !browser.isBlank()) {
                try {
                    return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(browser.getBytes(StandardCharsets.UTF_8)));
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }

    /** The current request's address, as the servlet container (behind the proxy's forwarded headers) has it. */
    private static @Nullable String address() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
