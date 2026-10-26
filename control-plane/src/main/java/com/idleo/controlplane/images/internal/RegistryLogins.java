package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Sealer;
import com.idleo.controlplane.images.CheckedImages;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Private registries' read-only tokens (ADR 0042): sealed under {@code images.registry-token.key}, with the login's id and
 * its renter's as associated data; opened only to pull, for the scanner's claim and for a StartSession, and each opening
 * counts as a use. Deleted {@code legal.retention.registry-tokens} after the last use ({@link ImagesRetention}). Never
 * logged, never in an audit row, never shown back.
 */
@Component
class RegistryLogins {

    private final JdbcClient jdbc;
    private final Sealer sealer;
    private final IdGenerator ids;
    private final Clock clock;

    RegistryLogins(JdbcClient jdbc, ImageProperties properties, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.sealer = new Sealer(
                properties.registryToken().key(),
                "images.registry-token.key (IMAGES_REGISTRY_TOKEN_KEY)",
                new SecureRandom());
        this.ids = ids;
        this.clock = clock;
    }

    private static String associated(UUID id, UUID userId) {
        return id + "/" + userId;
    }

    /** Keeps a renter's login for a registry; returns its id. */
    UUID keep(UUID userId, String registry, String username, String token) {
        var id = ids.newId();
        var now = Timestamp.from(Instant.now(clock));
        jdbc.sql("""
                        insert into images.registry_logins
                            (id, user_id, registry, username, sealed_token, created_at, last_used_at)
                        values (:id, :userId, :registry, :username, :sealed, :now, :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("registry", registry)
                .param("username", username)
                .param("sealed", sealer.seal(associated(id, userId), token))
                .param("now", now)
                .update();
        return id;
    }

    /** The login, opened, for one pull; marks it used. */
    Optional<CheckedImages.Login> open(UUID loginId) {
        record Stored(UUID userId, String username, byte[] sealed) {}
        var stored = jdbc.sql("select user_id, username, sealed_token from images.registry_logins where id = :id")
                .param("id", loginId)
                .query((rs, n) -> new Stored(
                        rs.getObject("user_id", UUID.class), rs.getString("username"), rs.getBytes("sealed_token")))
                .optional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("update images.registry_logins set last_used_at = :now where id = :id")
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("id", loginId)
                .update();
        var login = stored.get();
        return Optional.of(new CheckedImages.Login(
                login.username(), sealer.open(associated(loginId, login.userId()), login.sealed())));
    }

    /** Deletes a login no image of its renter's uses any more. */
    void forgetUnused(UUID loginId) {
        jdbc.sql("""
                        delete from images.registry_logins l
                        where l.id = :id and not exists (select 1 from images.images i where i.login_id = l.id)
                        """).param("id", loginId).update();
    }
}
