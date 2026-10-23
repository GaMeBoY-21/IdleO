package com.idleo.controlplane.billing.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Razorpay's two signatures (ADR 0039), as its documentation gives them: Checkout's payment signature is the
 * HMAC-SHA256 of {@code order_id + "|" + razorpay_payment_id} under the key secret, with the order id the one we stored,
 * never the one Checkout sent back; a webhook's {@code X-Razorpay-Signature} is the HMAC-SHA256 of the raw request body
 * under the webhook secret. Both are lower-case hex, compared in constant time.
 */
final class RazorpaySignatures {

    private RazorpaySignatures() {}

    static boolean paymentSignatureValid(
            String storedOrderId, String paymentId, @Nullable String signature, String keySecret) {
        return matches(hmac(keySecret, (storedOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature);
    }

    static boolean webhookSignatureValid(byte[] rawBody, @Nullable String signature, String webhookSecret) {
        return matches(hmac(webhookSecret, rawBody), signature);
    }

    /** The lower-case hex HMAC-SHA256 of {@code message} under {@code secret}. */
    static String hmac(String secret, byte[] message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 isn't available", e);
        }
    }

    private static boolean matches(String expected, @Nullable String given) {
        if (given == null || given.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                given.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
