package com.idleo.controlplane;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The shared kernel's beans. Every module takes "now" from this one clock, never from the system. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LegalSettings.class)
class SharedKernelConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
