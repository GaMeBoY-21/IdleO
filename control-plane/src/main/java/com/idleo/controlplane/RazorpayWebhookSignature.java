package com.idleo.controlplane;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Razorpay's webhook signature, which money in (ADR 0039) and RazorpayX's payouts (ADR 0040) both check
 * (https://razorpay.com/docs/webhooks/validate-test/): {@code X-Razorpay-Signature} is the lower-case hex HMAC-SHA256
 * of the raw request body under the webhook secret, compared in constant time, before the body is read at all.
 */
public final class RazorpayWebhookSignature {

    private RazorpayWebhookSignature() {}

    public static boolean valid(byte[] rawBody, @Nullable String signature, String webhookSecret) {
        return matches(hmac(webhookSecret, rawBody), signature);
    }

    /** The lower-case hex HMAC-SHA256 of {@code message} under {@code secret}. */
    public static String hmac(String secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 isn't available", e);
        }
    }

    /** Whether {@code given} is {@code expected}, in constant time; blank never is. */
    public static boolean matches(String expected, @Nullable String given) {
        if (given == null || given.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                given.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
