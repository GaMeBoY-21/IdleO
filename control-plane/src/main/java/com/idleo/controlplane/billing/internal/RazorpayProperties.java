package com.idleo.controlplane.billing.internal;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay (ADR 0039), from the environment: the keys from Razorpay's dashboard are never in code or settings files.
 * With no key id, payments are off and top-ups answer {@code payments-unavailable}.
 *
 * @param apiUrl Razorpay's API; a local stack and the tests point it at the fake (a development setting the prod
 *     profile refuses)
 * @param keyId {@code RAZORPAY_KEY_ID}: {@code rzp_test_…} in test mode, {@code rzp_live_…} live
 * @param keySecret {@code RAZORPAY_KEY_SECRET}: signs the API calls and checks Checkout's payment signature
 * @param webhookSecret {@code RAZORPAY_WEBHOOK_SECRET}: checks each webhook's {@code X-Razorpay-Signature}
 * @param previousWebhookSecret the secret before the last rotation, accepted until {@code previousWebhookSecretUntil}:
 *     Razorpay retries an event with the secret it was first sent under
 * @param timeout how long one call to Razorpay may take
 */
@ConfigurationProperties("billing.razorpay")
record RazorpayProperties(
        URI apiUrl,
        @Nullable String keyId,
        @Nullable String keySecret,
        @Nullable String webhookSecret,
        @Nullable String previousWebhookSecret,
        @Nullable Instant previousWebhookSecretUntil,
        Duration timeout) {

    static final URI RAZORPAY_API = URI.create("https://api.razorpay.com");

    /** Whether payments can be taken: a key and its secret are set. */
    boolean enabled() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    /** Whether these are live keys, which take real money. */
    boolean live() {
        return keyId != null && keyId.startsWith("rzp_live_");
    }
}
