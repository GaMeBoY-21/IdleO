package com.idleo.controlplane;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries event publications whose listener failed or never ran, for example after a crash between the
 * commit and the listener (ADR 0015). Spring Modulith records each publication in the same transaction as
 * the change that caused it; this hands the incomplete ones back to their listeners.
 *
 * <p>Each publication is retried at most {@link #MAX_ATTEMPTS} times, waiting twice as long before each try (from
 * {@code events.retry-older-than}, at most {@link #MAX_WAIT}). One that still fails then stays FAILED and is left
 * alone: retrying something that can never succeed only burns work and hides it. Those are counted by
 * {@code events.publications.failed_permanently} (EventPublicationMetrics) and alerted on (docs/runbooks/alerts.md,
 * "Events failing permanently").
 */
@Component
@Profile("!ops")
class EventPublicationRetry {

    /** About two hours of tries in production (30 s, doubling), long enough to outlast an outage of a dependency. */
    static final int MAX_ATTEMPTS = 8;

    static final Duration MAX_WAIT = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(EventPublicationRetry.class);

    private final IncompleteEventPublications incomplete;
    private final Duration olderThan;
    private final Clock clock;

    EventPublicationRetry(
            IncompleteEventPublications incomplete,
            @Value("${events.retry-older-than}") Duration olderThan,
            Clock clock) {
        this.incomplete = incomplete;
        this.olderThan = olderThan;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${events.retry-every}", initialDelayString = "${events.retry-every}")
    void retry() {
        try {
            var now = Instant.now(clock);
            incomplete.resubmitIncompletePublications(publication -> due(publication, now, olderThan));
        } catch (RuntimeException e) {
            log.warn("Retrying incomplete event publications failed; trying again later", e);
        }
    }

    /**
     * Whether {@code publication} is retried now: it has tries left, and has waited {@code first} doubled once for
     * each earlier try (at most {@link #MAX_WAIT}) since it was made or last retried.
     */
    static boolean due(EventPublication publication, Instant now, Duration first) {
        int attempts = publication.getCompletionAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }
        var wait = first.multipliedBy(1L << Math.min(attempts, 20));
        if (wait.compareTo(MAX_WAIT) > 0) {
            wait = MAX_WAIT;
        }
        var since = publication.getLastResubmissionDate() == null
                ? publication.getPublicationDate()
                : publication.getLastResubmissionDate();
        return !since.plus(wait).isAfter(now);
    }
}
