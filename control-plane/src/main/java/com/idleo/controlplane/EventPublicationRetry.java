package com.idleo.controlplane;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries event publications whose listener failed or never ran, for example after a crash between the
 * commit and the listener (ADR 0015). Spring Modulith records each publication in the same transaction as
 * the change that caused it; this hands the incomplete ones back to their listeners.
 */
@Component
@Profile("!ops")
class EventPublicationRetry {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationRetry.class);

    private final IncompleteEventPublications incomplete;
    private final Duration olderThan;

    EventPublicationRetry(
            IncompleteEventPublications incomplete, @Value("${events.retry-older-than}") Duration olderThan) {
        this.incomplete = incomplete;
        this.olderThan = olderThan;
    }

    @Scheduled(fixedDelayString = "${events.retry-every}", initialDelayString = "${events.retry-every}")
    void retry() {
        try {
            incomplete.resubmitIncompletePublicationsOlderThan(olderThan);
        } catch (RuntimeException e) {
            log.warn("Retrying incomplete event publications failed; trying again later", e);
        }
    }
}
