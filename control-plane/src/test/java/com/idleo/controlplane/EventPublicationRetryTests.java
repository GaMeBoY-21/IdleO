package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;

/**
 * An event that can never succeed isn't retried forever (EventPublicationRetry): each try waits twice as long as the
 * last, and after {@link EventPublicationRetry#MAX_ATTEMPTS} it's left FAILED, counted, and alerted on.
 */
@ControlPlaneTest
class EventPublicationRetryTests {

    private static final Duration FIRST = Duration.ofSeconds(30);
    private static final Instant MADE = Instant.parse("2026-10-30T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EventPublicationRetry retry;

    @Autowired
    MeterRegistry meters;

    private static EventPublication publication(int attempts, @Nullable Instant lastTried) {
        return new EventPublication() {
            @Override
            public UUID getIdentifier() {
                return UUID.randomUUID();
            }

            @Override
            public Object getEvent() {
                return "an event";
            }

            @Override
            public Instant getPublicationDate() {
                return MADE;
            }

            @Override
            public Optional<Instant> getCompletionDate() {
                return Optional.empty();
            }

            @Override
            public Status getStatus() {
                return Status.FAILED;
            }

            @Override
            public @Nullable Instant getLastResubmissionDate() {
                return lastTried;
            }

            @Override
            public int getCompletionAttempts() {
                return attempts;
            }
        };
    }

    @Test
    void theFirstRetryWaitsOnceAndEachLaterOneTwiceAsLong() {
        assertThat(EventPublicationRetry.due(
                        publication(0, null), MADE.plus(FIRST).minusSeconds(1), FIRST))
                .isFalse();
        assertThat(EventPublicationRetry.due(publication(0, null), MADE.plus(FIRST), FIRST))
                .isTrue();

        var tried = MADE.plus(Duration.ofMinutes(10));
        // Three tries so far: the fourth waits 30 s × 2³ = 4 minutes.
        assertThat(EventPublicationRetry.due(
                        publication(3, tried), tried.plus(Duration.ofMinutes(4)).minusSeconds(1), FIRST))
                .isFalse();
        assertThat(EventPublicationRetry.due(publication(3, tried), tried.plus(Duration.ofMinutes(4)), FIRST))
                .isTrue();
    }

    @Test
    void theWaitStopsGrowingAtAnHour() {
        var tried = MADE.plus(Duration.ofHours(3));
        assertThat(EventPublicationRetry.due(publication(7, tried), tried.plus(EventPublicationRetry.MAX_WAIT), FIRST))
                .isTrue();
    }

    @Test
    void afterTheLastTryItIsNeverRetried() {
        var tried = MADE.plus(Duration.ofHours(3));
        assertThat(EventPublicationRetry.due(
                        publication(EventPublicationRetry.MAX_ATTEMPTS, tried), tried.plus(Duration.ofDays(30)), FIRST))
                .isFalse();
    }

    @Test
    void aPublicationOutOfTriesIsLeftAloneAndCounted() {
        var gauge = meters.get("events.publications.failed_permanently").gauge();
        double before = gauge.value();
        var id = UUID.randomUUID();
        jdbc.update(
                """
                insert into modulith.event_publication
                    (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts,
                     last_resubmission_date)
                values (?, 'a.listener.that.always.fails', 'java.lang.String', '"an event"', ?, 'FAILED', ?, ?)
                """,
                id,
                Timestamp.from(Instant.now().minus(Duration.ofDays(1))),
                EventPublicationRetry.MAX_ATTEMPTS,
                Timestamp.from(Instant.now().minus(Duration.ofDays(1))));
        try {
            retry.retry();

            assertThat(jdbc.queryForObject(
                            "select completion_attempts from modulith.event_publication where id = ?",
                            Integer.class,
                            id))
                    .as("not tried again")
                    .isEqualTo(EventPublicationRetry.MAX_ATTEMPTS);
            assertThat(gauge.value()).isEqualTo(before + 1);
        } finally {
            jdbc.update("delete from modulith.event_publication where id = ?", id);
        }
    }
}
