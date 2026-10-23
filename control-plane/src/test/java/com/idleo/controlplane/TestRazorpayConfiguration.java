package com.idleo.controlplane;

import com.idleo.controlplane.razorpay.FakeRazorpay;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The fake Razorpay (ADR 0039) for every test: one per test JVM, on a free port, with the test keys and webhook
 * secret it signs with. Tests reach it as {@link #RAZORPAY}, and {@code reset()} it when they need a clean slate.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRazorpayConfiguration {

    public static final FakeRazorpay RAZORPAY = new FakeRazorpay(0);

    @Bean
    DynamicPropertyRegistrar razorpayProperties() {
        return registry -> {
            registry.add("billing.razorpay.api-url", RAZORPAY::url);
            registry.add("billing.razorpay.key-id", () -> FakeRazorpay.KEY_ID);
            registry.add("billing.razorpay.key-secret", () -> FakeRazorpay.KEY_SECRET);
            registry.add("billing.razorpay.webhook-secret", () -> FakeRazorpay.WEBHOOK_SECRET);
        };
    }
}
