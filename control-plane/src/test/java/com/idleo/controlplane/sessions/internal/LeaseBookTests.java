package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The control plane's lease record as unbroken stretches (ADR 0017). */
@ControlPlaneTest
class LeaseBookTests {

    private static final Duration TTL = Duration.ofMinutes(2);

    @Autowired
    LeaseBook book;

    @Autowired
    SessionStore store;

    @Autowired
    PlatformTransactionManager transactionManager;

    private void record(UUID sessionId, Instant sentAt) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> book.record(sessionId, 1, sentAt, TTL));
    }

    @Test
    void renewalsInsideTheLeaseExtendItAndALateOneLeavesAGap() {
        var sessionId = SessionTestData.requested(store, transactionManager, UUID.randomUUID(), 10, 20_000);
        var t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        record(sessionId, t0);
        record(sessionId, t0.plusSeconds(30));
        record(sessionId, t0.plusSeconds(60));

        assertThat(book.covered(sessionId, 1, t0, t0.plusSeconds(180))).isTrue();
        assertThat(book.covered(sessionId, 1, t0, t0.plusSeconds(181))).isFalse();

        // The lease ran out at 180 s; the next renewal went at 200 s.
        record(sessionId, t0.plusSeconds(200));
        assertThat(book.covered(sessionId, 1, t0.plusSeconds(150), t0.plusSeconds(210)))
                .isFalse();
        assertThat(book.covered(sessionId, 1, t0.plusSeconds(200), t0.plusSeconds(320)))
                .isTrue();
        assertThat(book.covered(sessionId, 2, t0, t0.plusSeconds(10)))
                .as("another attempt")
                .isFalse();
    }
}
