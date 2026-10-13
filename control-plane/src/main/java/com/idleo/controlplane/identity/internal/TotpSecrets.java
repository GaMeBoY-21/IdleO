package com.idleo.controlplane.identity.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
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

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random;

    TotpSecrets(String configured, SecureRandom random) {
        this.key = new SecretKeySpec(keyBytes(configured), "AES");
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

    /** The key: base64 of 32 bytes, or a {@code file:} location holding that. */
    private static byte[] keyBytes(String configured) {
        var text = configured.strip();
        if (text.startsWith("file:")) {
            try {
                text = Files.readString(Path.of(text.substring("file:".length())))
                        .strip();
            } catch (IOException e) {
                throw new UncheckedIOException("identity.admin.totp-key names a file that can't be read", e);
            }
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("identity.admin.totp-key must be 32 bytes in base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "identity.admin.totp-key must be 32 bytes in base64; it is " + bytes.length + " bytes");
        }
        return bytes;
    }
}
