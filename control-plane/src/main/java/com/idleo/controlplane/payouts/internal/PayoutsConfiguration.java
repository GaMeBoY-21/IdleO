package com.idleo.controlplane.payouts.internal;

import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The payouts module's settings, its PAN seal and its PAN check (ADR 0040). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PayoutsProperties.class, RazorpayXProperties.class})
class PayoutsConfiguration {

    @Bean
    PanSeal panSeal(PayoutsProperties properties) {
        return new PanSeal(properties.pan().key(), new SecureRandom());
    }

    @Bean
    PanVerifier panVerifier(PayoutsProperties properties) {
        return switch (properties.pan().verifier()) {
            case MANUAL -> new ManualPanVerifier();
            case FAKE -> new FakePanVerifier();
        };
    }
}
