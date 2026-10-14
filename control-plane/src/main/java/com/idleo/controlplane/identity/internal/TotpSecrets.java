package com.idleo.controlplane.identity.internal;

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

/**
 * Seals admins' TOTP secrets for the database (ADR 0027): AES-256-GCM under {@code identity.admin.totp-key}, a fresh
 * 96-bit nonce each time, stored first, and the admin's user id as associated data, so a sealed secret copied to
 * another admin's row doesn't open. The key never leaves this class.
 */
final class TotpSecrets {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random;

    TotpSecrets(String configured, SecureRandom random) {
        this.key = new SecretKeySpec(ConfiguredKeys.read(configured, "identity.admin.totp-key"), "AES");
        this.random = random;
    }

    byte[] seal(UUID userId, byte[] secret) {
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        var sealed = cipher(Cipher.ENCRYPT_MODE, userId, nonce, secret);
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                .put(nonce)
                .put(sealed)
                .array();
    }

    byte[] open(UUID userId, byte[] stored) {
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalStateException("a sealed TOTP secret is too short");
        }
        var nonce = Arrays.copyOf(stored, NONCE_BYTES);
        return cipher(Cipher.DECRYPT_MODE, userId, nonce, Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
    }

    private byte[] cipher(int mode, UUID userId, byte[] nonce, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(userId.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "a TOTP secret couldn't be sealed or opened with identity.admin.totp-key", e);
        }
    }
}
