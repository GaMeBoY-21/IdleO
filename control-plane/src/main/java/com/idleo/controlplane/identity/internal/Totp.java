package com.idleo.controlplane.identity.internal;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238), as every authenticator app makes them: HMAC-SHA1 over 30-second time
 * steps, 6 digits, from a 160-bit secret written in base32 (RFC 4648) for the app. A code is accepted for the step
 * it was made in or the one either side, to allow for a phone's clock and the time it takes to type.
 */
final class Totp {

    static final int SECRET_BYTES = 20;
    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;
    /** Steps either side of now a code may come from. */
    static final int DRIFT_STEPS = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    static byte[] newSecret(SecureRandom random) {
        var secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one time step (RFC 4226 section 5.3, with RFC 6238's counter). */
    static String code(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            var hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The time step {@code code} was made for, if it's one of the steps around {@code now} and later than {@code
     * afterStep}, so a code that was accepted once is never accepted again. Compared in constant time.
     */
    static OptionalLong verify(byte[] secret, String code, Instant now, long afterStep) {
        var typed = code == null ? "" : code.replaceAll("\\s", "");
        if (!typed.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            if (step > afterStep
                    && MessageDigest.isEqual(
                            code(secret, step).getBytes(StandardCharsets.US_ASCII),
                            typed.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** The secret as an authenticator app takes it typed in: base32 without padding. */
    static String base32(byte[] bytes) {
        var out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /**
     * The {@code otpauth://} link an authenticator app reads from a QR code (Google Authenticator's key URI format):
     * the issuer and the account name, the secret, and the defaults spelled out.
     */
    static String uri(String issuer, String account, byte[] secret) {
        var label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
