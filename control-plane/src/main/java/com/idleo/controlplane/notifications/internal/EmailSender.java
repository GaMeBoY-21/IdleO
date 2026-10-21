package com.idleo.controlplane.notifications.internal;

import org.jspecify.annotations.Nullable;

/**
 * The one way an email leaves the control plane (ADR 0026). Today one implementation speaks SMTP, to a local mail
 * catcher in development and to the production provider later; nothing else knows which, so choosing a provider
 * changes configuration, not code (docs/runbooks/email.md).
 */
interface EmailSender {

    /**
     * A plain-text email with a simple HTML twin; no images, no tracking.
     *
     * @param messageId the {@code Message-ID}, the same for every try at one notification, so a resend after a retry
     *     is the same email to the person's mail server
     * @param unsubscribe where a mail client's unsubscribe button goes ({@code List-Unsubscribe}): the person's email
     *     choices, for the kinds they can switch off; null for money and safety, which are always sent
     */
    record Email(
            String to,
            String subject,
            String text,
            String html,
            String messageId,
            @Nullable String unsubscribe) {}

    /** The mail server didn't take it. The caller may try again. */
    class EmailFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        EmailFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    void send(Email email);
}
