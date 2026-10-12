package com.idleo.controlplane.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The shape of an API key (ADR 0025): {@code idleo_} and 32 random bytes in lower-case base32 without padding, 58
 * characters in all. Only {@link #hash} of a key is stored, and {@link #displayPrefix} for the person to recognise it
 * by. 256 random bits need no slow hash: there is nothing to guess.
 */
final class ApiKeySecrets {

    static final String PREFIX = "idleo_";
    private static final int RANDOM_BYTES = 32;
    /** Characters after the prefix kept for display: 40 of the key's 256 bits. */
    private static final int SHOWN = 8;

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private ApiKeySecrets() {}

    /** Whether a bearer value is meant as an API key rather than an ID token. */
    static boolean looksLikeKey(String bearer) {
        return bearer.startsWith(PREFIX);
    }

    static String generate(SecureRandom random) {
        var bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return PREFIX + base32(bytes);
    }

    static byte[] hash(String key) {
        return sha256(key);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a list shows of a key: the prefix and its first characters, like {@code idleo_k3j9x2mq}. */
    static String displayPrefix(String key) {
        return key.substring(0, PREFIX.length() + SHOWN);
    }

    /** Lower-case base32 without padding (RFC 4648 alphabet). */
    static String base32(byte[] bytes) {
        var out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }
}
