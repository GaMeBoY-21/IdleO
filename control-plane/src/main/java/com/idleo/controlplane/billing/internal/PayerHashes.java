package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ConfiguredKeys;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who paid, as a keyed hash: an HMAC-SHA-256 of the UPI ID Razorpay names on a payment, under a secret pepper. UPI IDs are
 * easy to guess, so a plain hash would give them back; this one is only good for telling that two payments came from the
 * same ID, the linked-accounts signal "same payer", and it's used for nothing else. The ID itself is never kept or logged.
 * Without a pepper, nothing is kept and the signal stays quiet (docs/runbooks/payer-upi-pepper.md).
 */
@Component
class PayerHashes {

    private static final Logger log = LoggerFactory.getLogger(PayerHashes.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final Map<Integer, byte[]> peppers = new LinkedHashMap<>();
    private final JdbcClient jdbc;
    private final Clock clock;

    PayerHashes(PayerHashProperties properties, JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        if (present(properties.pepper())) {
            peppers.put(
                    properties.pepperVersion(), ConfiguredKeys.read(properties.pepper(), "billing.payer-hash.pepper"));
        } else {
            log.warn("No payer pepper is set (BILLING_PAYER_PEPPER): the same-payer signal is off");
        }
        if (present(properties.previousPepper())) {
            peppers.put(
                    properties.previousPepperVersion(),
                    ConfiguredKeys.read(properties.previousPepper(), "billing.payer-hash.previous-pepper"));
        }
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /** Keeps the hash of the UPI ID behind this top-up, under each pepper there is, in the caller's transaction. */
    void keep(UUID topUpId, UUID userId, @Nullable String vpa) {
        if (vpa == null || vpa.isBlank()) {
            return;
        }
        peppers.forEach((version, pepper) -> jdbc.sql("""
                        insert into billing.payer_hashes (topup_id, key_version, user_id, hash, created_at)
                        values (:topUp, :version, :user, :hash, :now)
                        on conflict do nothing
                        """)
                .param("topUp", topUpId)
                .param("version", version)
                .param("user", userId)
                .param("hash", hash(pepper, vpa))
                .param("now", Timestamp.from(clock.instant()))
                .update());
    }

    /** UPI IDs are case-insensitive, and Razorpay may give either. */
    static byte[] hash(byte[] pepper, String vpa) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            return mac.doFinal(vpa.strip().toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 isn't available", e);
        }
    }
}
