package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.RazorpayWebhookSignature;
import java.nio.charset.StandardCharsets;
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
        return RazorpayWebhookSignature.matches(
                RazorpayWebhookSignature.hmac(
                        keySecret, (storedOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)),
                signature);
    }

    static boolean webhookSignatureValid(byte[] rawBody, @Nullable String signature, String webhookSecret) {
        return RazorpayWebhookSignature.valid(rawBody, signature, webhookSecret);
    }
}
