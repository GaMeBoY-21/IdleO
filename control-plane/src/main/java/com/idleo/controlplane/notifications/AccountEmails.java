package com.idleo.controlplane.notifications;

import java.util.UUID;

/**
 * What the privacy module tells a person about their account and their data (ADR 0037): always sent, whatever their
 * choices, because it's about their account. The notifications module implements it; nothing it sends names anyone
 * but the person.
 */
public interface AccountEmails {

    /**
     * Tells the person in the app and by email, once for {@code dedupeKey}: their export is ready, their account will
     * be deleted on a date, or the day before.
     *
     * @param link a path in the web app, such as {@code /account/data/}
     * @throws RuntimeException when the mail server refused it; the caller tries again later
     */
    void tell(UUID userId, String dedupeKey, String title, String body, String link);

    /**
     * One email to {@code to}, kept nowhere: a link that is itself a secret, or the last word to an account that's
     * gone. {@code messageId} names it however often it's tried.
     *
     * @throws RuntimeException when the mail server refused it; the caller tries again later
     */
    void sendOnce(String to, UUID messageId, String title, String body, String link);
}
