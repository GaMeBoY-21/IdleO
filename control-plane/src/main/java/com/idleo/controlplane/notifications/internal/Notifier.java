package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.identity.UserDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells one person one thing (ADR 0026): checks their choices, keeps the message for their list, and emails it.
 * An event delivered again, as the durable event registry may after a failure, finds its message already made, and
 * the email is handed to the mail server at most once: it is claimed in its own transaction before it is sent, and
 * the claim goes back only if the mail server refuses it, so the retry sends it. Each hand-over counts in
 * {@code notifications.emails} by {@code outcome} ({@code sent}, {@code failed}), which the email alert reads.
 */
@Component
class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final NotificationPreferences preferences;
    private final NotificationStore store;
    private final UserDirectory users;
    private final EmailContent content;
    private final EmailSender sender;
    private final TransactionTemplate own;
    private final MeterRegistry registry;

    Notifier(
            NotificationPreferences preferences,
            NotificationStore store,
            UserDirectory users,
            EmailContent content,
            EmailSender sender,
            PlatformTransactionManager transactions,
            MeterRegistry registry) {
        this.registry = registry;
        this.preferences = preferences;
        this.store = store;
        this.users = users;
        this.content = content;
        this.sender = sender;
        // Each step commits on its own, so it holds whatever the listener's transaction does afterwards.
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param dedupeKey names the event that caused this ("session-ended:" and the session id), so the same
     *     event never makes a second message
     * @throws EmailSender.EmailFailedException when the mail server refused the email; the caller lets it
     *     propagate and the event is retried
     */
    void tell(UUID userId, NotificationKind kind, String dedupeKey, Message message) {
        var choice = preferences.read(userId).get(kind);
        // Locked kinds are on whatever was stored; the API never lets them be switched off (ADR 0024).
        boolean inApp = choice.inApp() || kind.locked();
        boolean emailWanted = kind.emailed() && (choice.email() || kind.locked());
        if (!inApp && !emailWanted) {
            return;
        }
        var stored = own.execute(status -> store.make(userId, kind, dedupeKey, message, inApp, emailWanted));
        if (stored == null || !stored.emailWanted() || stored.emailSentAt() != null) {
            return;
        }
        var to = users.verifiedEmail(userId);
        if (to.isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(own.execute(status -> store.claimEmail(stored.id())))) {
            return;
        }
        try {
            sender.send(content.of(to.get(), kind, stored.message()));
        } catch (RuntimeException refused) {
            own.executeWithoutResult(status -> store.releaseEmail(stored.id()));
            registry.counter("notifications.emails", "outcome", "failed").increment();
            throw refused;
        }
        own.executeWithoutResult(status -> store.markEmailSent(stored.id()));
        registry.counter("notifications.emails", "outcome", "sent").increment();
        log.info("Emailed message {} ({})", stored.id(), kind.slug());
    }
}
