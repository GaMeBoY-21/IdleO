package com.idleo.controlplane.notifications.internal;

/**
 * The one way an email leaves the control plane (ADR 0026). Today one implementation speaks SMTP, to a local mail
 * catcher in development and to the production provider later; nothing else knows which, so choosing a provider
 * changes configuration, not code (docs/runbooks/email.md).
 */
interface EmailSender {

    /** A plain-text email with a simple HTML twin; no images, no tracking. */
    record Email(String to, String subject, String text, String html) {}

    /** The mail server didn't take it. The caller may try again. */
    class EmailFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        EmailFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    void send(Email email);
}
