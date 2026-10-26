package com.idleo.controlplane;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Seals a secret for the database: AES-256-GCM under a configured key, a fresh 96-bit nonce each time, stored first, and
 * associated data naming whose and which it is, so a sealed value copied to another row doesn't open. PANs (ADR 0040) and
 * registry tokens (ADR 0042) are sealed with it, each under a key of its own. The key never leaves the instance. Without a
 * key (a command-line run that never touches such a secret) it refuses only when it's used; the prod profile refuses to
 * start without one.
 */
public final class Sealer {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final @Nullable SecretKey key;
    private final String setting;
    private final SecureRandom random;

    /**
     * @param configured the key as configured (32 bytes, base64, or {@code file:…}); blank for none
     * @param setting the key's setting, for messages
     */
    public Sealer(@Nullable String configured, String setting, SecureRandom random) {
        this.key = configured == null || configured.isBlank()
                ? null
                : new SecretKeySpec(ConfiguredKeys.read(configured, setting), "AES");
        this.setting = setting;
        this.random = random;
    }

    /** {@code secret} sealed, bound to {@code associated}. */
    public byte[] seal(String associated, String secret) {
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        var sealed = cipher(Cipher.ENCRYPT_MODE, associated, nonce, secret.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                .put(nonce)
                .put(sealed)
                .array();
    }

    /** What {@link #seal} sealed with the same {@code associated}; anything else throws. */
    public String open(String associated, byte[] stored) {
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalStateException("a sealed value is too short");
        }
        var nonce = Arrays.copyOf(stored, NONCE_BYTES);
        return new String(
                cipher(Cipher.DECRYPT_MODE, associated, nonce, Arrays.copyOfRange(stored, NONCE_BYTES, stored.length)),
                StandardCharsets.UTF_8);
    }

    private byte[] cipher(int mode, String associated, byte[] nonce, byte[] input) {
        if (key == null) {
            throw new IllegalStateException(setting + " isn't set, so nothing can be sealed or opened with it");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("a value couldn't be sealed or opened with " + setting, e);
        }
    }
}
