package com.idleo.controlplane;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The shared kernel's beans. Every module takes "now" from this one clock, never from the system. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LegalSettings.class, RetentionPeriods.class})
class SharedKernelConfiguration {

    @Bean
    Clock clock(@Value("${e2e.clock-file:}") String clockFile) {
        return clockFile.isBlank() ? Clock.systemUTC() : new OffsetFileClock(Path.of(clockFile));
    }

    @Bean
    IdTokenClock idTokenClock(Clock clock, @Value("${e2e.clock-file:}") String clockFile) {
        return new IdTokenClock(clockFile.isBlank() ? clock : Clock.systemUTC());
    }
}
