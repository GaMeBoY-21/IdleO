package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.identity.EmailDeliverability;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Addresses the email provider told us not to send to (ADR 0036), kept as their SHA-256, never the address. A hard
 * bounce stops every email to the address: it can't be delivered, money and safety included, and the person sees "We
 * couldn't email you: check your address" until their address changes, which is a different address. A spam complaint
 * stops the kinds they can switch off; the ones about money and safety still go.
 */
@Repository
class EmailSuppressions implements EmailDeliverability {

    enum Reason {
        BOUNCED,
        COMPLAINED;

        String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    EmailSuppressions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records what the provider said about an address; a bounce outranks a complaint, and a repeat changes nothing. */
    void record(String address, Reason reason, String eventId) {
        jdbc.sql("""
                        insert into notifications.email_suppressions (address_hash, reason, event_id, created_at)
                        values (:hash, :reason, :eventId, :now)
                        on conflict (address_hash) do update
                        set reason = 'bounced', event_id = excluded.event_id, created_at = excluded.created_at
                        where excluded.reason = 'bounced' and notifications.email_suppressions.reason <> 'bounced'
                        """)
                .param("hash", hash(address))
                .param("reason", reason.dbValue())
                .param("eventId", eventId)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
    }

    Optional<Reason> reasonFor(String address) {
        return jdbc.sql("select reason from notifications.email_suppressions where address_hash = :hash")
                .param("hash", hash(address))
                .query(String.class)
                .optional()
                .map(reason -> Reason.valueOf(reason.toUpperCase(Locale.ROOT)));
    }

    /** Whether this kind of email must not go to this address. */
    boolean blocks(String address, NotificationKind kind) {
        return reasonFor(address)
                .map(reason -> reason == Reason.BOUNCED || !kind.locked())
                .orElse(false);
    }

    @Override
    public boolean undeliverable(String address) {
        return reasonFor(address).filter(reason -> reason == Reason.BOUNCED).isPresent();
    }

    static byte[] hash(String address) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(address.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always there", e);
        }
    }
}
