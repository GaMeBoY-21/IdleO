package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Trusts the two local test issuers, the way production trusts the configured providers. */
@TestConfiguration(proxyBeanMethods = false)
class TestIssuersConfiguration {

    @Bean
    DynamicPropertyRegistrar trustedTestIssuers() {
        var issuers = TestIssuers.get();
        return registry -> {
            registry.add("identity.issuers[0].issuer-uri", () -> issuers.issuerA);
            registry.add("identity.issuers[0].audience", () -> TestIssuers.AUDIENCE_A);
            registry.add("identity.issuers[1].issuer-uri", () -> issuers.issuerB);
            registry.add("identity.issuers[1].audience", () -> TestIssuers.AUDIENCE_B);
        };
    }
}
