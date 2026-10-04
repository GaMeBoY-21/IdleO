package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application's clock with one tests can move forward. */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
