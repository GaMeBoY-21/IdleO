package com.idleo.controlplane;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.UUIDClock;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one source of ids: UUIDv7 (RFC 9562), time-ordered, created in the application before insert.
 * Its time comes from the shared {@link Clock}.
 */
@Component
public class IdGenerator {

    private final TimeBasedEpochGenerator generator;

    IdGenerator(Clock clock) {
        this.generator = Generators.timeBasedEpochGenerator(new SecureRandom(), new UUIDClock() {
            @Override
            public long currentTimeMillis() {
                return clock.millis();
            }
        });
    }

    public UUID newId() {
        return generator.generate();
    }
}
