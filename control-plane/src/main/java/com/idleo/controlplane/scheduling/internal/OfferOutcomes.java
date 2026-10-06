package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.agentgateway.OfferReplies;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.SessionBookings;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an offer's answer does (ADR 0015). Accepted in time: the session moves to PROVISIONING. Declined or
 * expired: in one transaction the offer is closed with its reason, the hold is released (a first attempt's
 * only), and the session goes back to REQUESTED for the next candidate, or to NO_HOST_FREE after the third
 * offer for the attempt. A late reply,
 * a reply to a withdrawn offer, or one for an unknown offer is ignored (but still acked).
 */
@Component
class OfferOutcomes implements OfferReplies {

    private static final Logger log = LoggerFactory.getLogger(OfferOutcomes.class);
    private static final int EXPIRY_BATCH = 50;

    private final OfferStore offers;
    private final SessionBookings sessions;
    private final CreditReservations reservations;
    private final ApplicationEventPublisher events;
    private final SchedulingProperties properties;
    private final SchedulingMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    OfferOutcomes(
            OfferStore offers,
            SessionBookings sessions,
            CreditReservations reservations,
            ApplicationEventPublisher events,
            SchedulingProperties properties,
            SchedulingMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.offers = offers;
        this.sessions = sessions;
        this.reservations = reservations;
        this.events = events;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void onReply(UUID hostId, UUID offerId, boolean accepted, String declineReason) {
        transactions.executeWithoutResult(status -> {
            var offer = offers.lock(offerId).orElse(null);
            var now = Instant.now(clock);
            if (offer == null || !offer.hostId().equals(hostId)) {
                log.info("Ignored host {}'s reply to unknown offer {}", hostId, offerId);
                return;
            }
            if (!offer.status().equals("pending") || now.isAfter(offer.expiresAt())) {
                log.info("Ignored host {}'s late reply to offer {} ({})", hostId, offerId, offer.status());
                metrics.offerAnswered("ignored");
                return;
            }
            if (accepted) {
                if (sessions.offerAccepted(offer.sessionId(), offer.gpuUuid())) {
                    offers.answer(offerId, "accepted", null, now);
                    metrics.offerAnswered("accepted");
                } else {
                    // The session moved on (cancelled) before the reply arrived.
                    offers.answer(offerId, "withdrawn", null, now);
                    metrics.offerAnswered("ignored");
                }
                return;
            }
            close(offer, "declined", declineReason.isBlank() ? "declined" : declineReason, now);
        });
    }

    /** Pending offers past their expiry count as declined with reason {@code expired}. */
    void expire() {
        transactions.executeWithoutResult(status -> {
            var now = Instant.now(clock);
            for (var offer : offers.lockExpired(now, EXPIRY_BATCH)) {
                close(offer, "expired", "expired", now);
            }
        });
    }

    /** Withdraws a cancelled session's open offers; a later accept is ignored. */
    void withdraw(UUID sessionId) {
        transactions.executeWithoutResult(status -> offers.withdrawPending(sessionId, Instant.now(clock)));
    }

    /**
     * A first attempt's hold is released with each declined offer and taken again with the next match. A
     * session being matched again after a lost host keeps its one hold across offers (ADR 0016); it's
     * released only if no host takes it.
     */
    private void close(OfferStore.Offer offer, String status, String reason, Instant now) {
        offers.answer(offer.id(), status, reason, now);
        metrics.offerAnswered(status);
        if (offers.count(offer.sessionId(), offer.sessionAttempt()) >= properties.maxOffers()) {
            if (sessions.noHostFree(offer.sessionId(), "offers_exhausted")) {
                reservations.release(offer.sessionId());
                metrics.outcome("no_host_free");
            }
            return;
        }
        if (offer.sessionAttempt() == 1) {
            reservations.release(offer.sessionId());
        }
        if (sessions.offerDeclined(offer.sessionId(), "offer_" + reason)) {
            events.publishEvent(new MatchRequested(offer.sessionId()));
        }
    }
}
