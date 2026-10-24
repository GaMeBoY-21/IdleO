package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.ConfiguredKeys;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Seals PANs for the database (ADR 0040), as the admin console's TOTP secrets are (ADR 0027): AES-256-GCM under {@code
 * payouts.pan.key}, a fresh 96-bit nonce each time, stored first, and the person's id as associated data, so a sealed
 * PAN copied to another person's row doesn't open. The key never leaves this class. Without a key (a command-line run
 * that never touches a PAN), it refuses only when it's used; the prod profile refuses to start without one.
 */
final class PanSeal {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final @Nullable SecretKey key;
    private final SecureRandom random;

    PanSeal(@Nullable String configured, SecureRandom random) {
        this.key = configured == null || configured.isBlank()
                ? null
                : new SecretKeySpec(ConfiguredKeys.read(configured, "payouts.pan.key"), "AES");
        this.random = random;
    }

    byte[] seal(UUID userId, String pan) {
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        var sealed = cipher(Cipher.ENCRYPT_MODE, userId, nonce, pan.getBytes(StandardCharsets.US_ASCII));
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                .put(nonce)
                .put(sealed)
                .array();
    }

    String open(UUID userId, byte[] stored) {
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalStateException("a sealed PAN is too short");
        }
        var nonce = Arrays.copyOf(stored, NONCE_BYTES);
        return new String(
                cipher(Cipher.DECRYPT_MODE, userId, nonce, Arrays.copyOfRange(stored, NONCE_BYTES, stored.length)),
                StandardCharsets.US_ASCII);
    }

    private byte[] cipher(int mode, UUID userId, byte[] nonce, byte[] input) {
        if (key == null) {
            throw new IllegalStateException("payouts.pan.key isn't set (PAYOUTS_PAN_KEY), so no PAN can be sealed");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("a PAN couldn't be sealed or opened with payouts.pan.key", e);
        }
    }
}
