package com.idleo.controlplane.payouts.internal;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RazorpayX (ADR 0040), from the environment, apart from money in's Razorpay keys: the keys from RazorpayX's dashboard
 * are never in code or settings files. With no key id, payouts are off: nothing is checked or paid.
 *
 * @param apiUrl RazorpayX's API (Razorpay's); a local stack and the tests point it at the fake (a development setting
 *     the prod profile refuses)
 * @param keyId {@code RAZORPAYX_KEY_ID}: {@code rzp_test_…} in test mode, {@code rzp_live_…} live
 * @param keySecret {@code RAZORPAYX_KEY_SECRET}
 * @param webhookSecret {@code RAZORPAYX_WEBHOOK_SECRET}: checks each webhook's {@code X-Razorpay-Signature}
 * @param accountNumber {@code RAZORPAYX_ACCOUNT_NUMBER}: the business account payouts and checks are paid from (test
 *     mode's differs from live's)
 * @param timeout how long one call may take
 */
@ConfigurationProperties("payouts.razorpayx")
record RazorpayXProperties(
        URI apiUrl,
        @Nullable String keyId,
        @Nullable String keySecret,
        @Nullable String webhookSecret,
        @Nullable String accountNumber,
        Duration timeout) {

    static final URI RAZORPAY_API = URI.create("https://api.razorpay.com");

    /** Whether payouts can be made: a key, its secret and the account to pay from are set. */
    boolean enabled() {
        return present(keyId) && present(keySecret) && present(accountNumber);
    }

    /** Whether these are live keys, which move real money. */
    boolean live() {
        return keyId != null && keyId.startsWith("rzp_live_");
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
