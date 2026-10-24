package com.idleo.controlplane;

import com.idleo.controlplane.razorpay.FakeRazorpay;
import com.idleo.controlplane.razorpay.FakeRazorpayX;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The fake Razorpay (ADR 0039) and the fake RazorpayX (ADR 0040) for every test: one each per test JVM, on a free port,
 * with the test keys and webhook secret it signs with. Tests reach them as {@link #RAZORPAY} and {@link #RAZORPAYX},
 * and {@code reset()} one when they need a clean slate.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRazorpayConfiguration {

    public static final FakeRazorpay RAZORPAY = new FakeRazorpay(0);

    /** The fake RazorpayX (ADR 0040), for payouts, with its own keys, webhook secret and account. */
    public static final FakeRazorpayX RAZORPAYX = new FakeRazorpayX(0);

    @Bean
    DynamicPropertyRegistrar razorpayProperties() {
        return registry -> {
            registry.add("billing.razorpay.api-url", RAZORPAY::url);
            registry.add("billing.razorpay.key-id", () -> FakeRazorpay.KEY_ID);
            registry.add("billing.razorpay.key-secret", () -> FakeRazorpay.KEY_SECRET);
            registry.add("billing.razorpay.webhook-secret", () -> FakeRazorpay.WEBHOOK_SECRET);
            registry.add("payouts.razorpayx.api-url", RAZORPAYX::url);
            registry.add("payouts.razorpayx.key-id", () -> FakeRazorpayX.KEY_ID);
            registry.add("payouts.razorpayx.key-secret", () -> FakeRazorpayX.KEY_SECRET);
            registry.add("payouts.razorpayx.webhook-secret", () -> FakeRazorpayX.WEBHOOK_SECRET);
            registry.add("payouts.razorpayx.account-number", () -> FakeRazorpayX.ACCOUNT_NUMBER);
        };
    }
}
