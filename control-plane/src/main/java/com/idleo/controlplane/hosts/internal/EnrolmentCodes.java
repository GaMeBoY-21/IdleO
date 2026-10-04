package com.idleo.controlplane.hosts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * One-time enrolment codes: 100 random bits as 20 Crockford base32 symbols, shown as {@code
 * XXXXX-XXXXX-XXXXX-XXXXX} so a person can copy one into the installer. The alphabet has no I, L, O
 * or U, and decoding maps the look-alikes (O to 0, I and L to 1) and ignores case, hyphens and
 * spaces. Only the SHA-256 of a code is stored, and codes are never logged.
 */
final class EnrolmentCodes {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int SYMBOLS = 20;
    private static final int GROUP = 5;

    private EnrolmentCodes() {}

    /** A new code, formatted for display. 20 symbols of 5 bits each: 100 bits from SecureRandom. */
    static String generate(SecureRandom random) {
        var code = new StringBuilder(SYMBOLS + 3);
        for (int i = 0; i < SYMBOLS; i++) {
            if (i > 0 && i % GROUP == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** The canonical form (20 symbols, no hyphens), or empty if the input can't be a code. */
    static Optional<String> normalise(String input) {
        var canonical = new StringBuilder(SYMBOLS);
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '-', ' ' -> {}
                case 'O' -> canonical.append('0');
                case 'I', 'L' -> canonical.append('1');
                default -> {
                    if (ALPHABET.indexOf(c) < 0) {
                        return Optional.empty();
                    }
                    canonical.append(c);
                }
            }
        }
        return canonical.length() == SYMBOLS ? Optional.of(canonical.toString()) : Optional.empty();
    }

    /** What the database stores: SHA-256 of the canonical code. */
    static byte[] hash(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
