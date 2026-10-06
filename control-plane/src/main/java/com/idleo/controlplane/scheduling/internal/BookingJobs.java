package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.sessions.SessionCancelled;
import com.idleo.controlplane.sessions.SessionRequested;
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
 * listens), and from the offer-expiry sweep. A listener that throws leaves its publication incomplete,
 * and it's retried.
 */
@Component
class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

    private final SessionMatcher matcher;
    private final OfferOutcomes outcomes;
    private final AgentCommands commands;
    private final TransactionTemplate transactions;

    BookingJobs(
            SessionMatcher matcher,
            OfferOutcomes outcomes,
            AgentCommands commands,
            PlatformTransactionManager transactionManager) {
        this.matcher = matcher;
        this.outcomes = outcomes;
        this.commands = commands;
        this.transactions = new TransactionTemplate(transactionManager);
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

    @Scheduled(fixedDelayString = "${scheduling.offer-sweep-interval}")
    void expireOffers() {
        try {
            outcomes.expire();
        } catch (RuntimeException e) {
            log.warn("The offer expiry sweep failed; it runs again shortly", e);
        }
    }
}
