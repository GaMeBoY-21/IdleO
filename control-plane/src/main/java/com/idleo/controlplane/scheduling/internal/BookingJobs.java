package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.sessions.SessionBookings;
import com.idleo.controlplane.sessions.SessionCancelled;
import com.idleo.controlplane.sessions.SessionRequested;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How booking work starts (ADR 0015): from durable events (the sessions module publishes, the scheduler
 * listens), from the offer-expiry sweep, and from retries of re-matches still waiting for a host (ADR 0016). A listener that throws leaves its publication incomplete,
 * and it's retried.
 */
@Component
class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

    private final SessionMatcher matcher;
    private final OfferOutcomes outcomes;
    private final AgentCommands commands;
    private final SessionBookings sessions;
    private final SchedulingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    BookingJobs(
            SessionMatcher matcher,
            OfferOutcomes outcomes,
            AgentCommands commands,
            SessionBookings sessions,
            SchedulingProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.matcher = matcher;
        this.outcomes = outcomes;
        this.commands = commands;
        this.sessions = sessions;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(SessionRequested event) {
        matcher.match(event.sessionId());
    }

    @Async
    @TransactionalEventListener
    void on(MatchRequested event) {
        matcher.match(event.sessionId());
    }

    @Async
    @TransactionalEventListener
    void on(SessionCancelled event) {
        outcomes.withdraw(event.sessionId());
        transactions.executeWithoutResult(status -> commands.withdraw(event.sessionId(), "offer"));
    }

    /**
     * Sessions being matched again after a lost host that no host could take yet (ADR 0016), and first bookings that
     * missed their match while presence couldn't be read (SessionMatcher): tried again, until they've waited
     * {@code rematch-wait}, then NO_HOST_FREE with the hold released.
     */
    @Scheduled(fixedDelayString = "${scheduling.rematch-retry-every}")
    void retryRematches() {
        try {
            var giveUpBefore = Instant.now(clock).minus(properties.rematchWait());
            for (var waiting : sessions.awaitingRematch()) {
                if (waiting.since().isBefore(giveUpBefore)) {
                    matcher.endWithoutHost(waiting.sessionId(), "no_host_for_rematch");
                } else {
                    matcher.match(waiting.sessionId());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Retrying re-matches failed; it runs again shortly", e);
        }
    }

    @Scheduled(fixedDelayString = "${scheduling.offer-sweep-interval}")
    void expireOffers() {
        try {
            outcomes.expire();
        } catch (RuntimeException e) {
            log.warn("The offer expiry sweep failed; it runs again shortly", e);
        }
    }
}
