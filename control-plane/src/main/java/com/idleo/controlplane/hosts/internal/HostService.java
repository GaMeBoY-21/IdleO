package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdGenerator;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hosts and their enrolment codes, always scoped to the owner. */
@Service
class HostService {

    static final Duration CODE_LIFETIME = Duration.ofHours(1);

    private final HostRepository hosts;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    HostService(HostRepository hosts, JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.hosts = hosts;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    record IssuedCode(String code, Instant expiresAt) {}

    Host create(UUID ownerUserId, String name) {
        return hosts.save(Host.newHost(ids.newId(), ownerUserId, name, Instant.now(clock)));
    }

    Optional<Host> find(UUID ownerUserId, UUID hostId) {
        return hosts.findByIdAndOwnerUserId(hostId, ownerUserId);
    }

    List<Host> list(UUID ownerUserId) {
        return hosts.findAllByOwnerUserIdOrderByCreatedAt(ownerUserId);
    }

    /** A new one-time code for the host, replacing any earlier one; empty if it isn't the owner's. */
    @Transactional
    Optional<IssuedCode> issueCode(UUID ownerUserId, UUID hostId) {
        if (find(ownerUserId, hostId).isEmpty()) {
            return Optional.empty();
        }
        var now = Instant.now(clock);
        var code = EnrolmentCodes.generate(random);
        var expiresAt = now.plus(CODE_LIFETIME);
        jdbc.sql("""
                        insert into hosts.enrolment_codes (host_id, code_hash, expires_at, created_at, used_at)
                        values (:hostId, :hash, :expiresAt, :now, null)
                        on conflict (host_id) do update
                        set code_hash = excluded.code_hash, expires_at = excluded.expires_at,
                            created_at = excluded.created_at, used_at = null
                        """)
                .param("hostId", hostId)
                .param(
                        "hash",
                        EnrolmentCodes.hash(EnrolmentCodes.normalise(code).orElseThrow()))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
        return Optional.of(new IssuedCode(code, expiresAt));
    }
}
