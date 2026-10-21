package com.idleo.controlplane.notifications.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code notifications.web-app-url} ({@code NOTIFICATIONS_WEB_APP_URL}): the web app, where an email's links go;
 * the prod profile refuses one that is plain HTTP or on this machine. {@code notifications.email.from}
 * ({@code NOTIFICATIONS_EMAIL_FROM}): the sender's address, which the production provider must allow (ADR 0026,
 * docs/runbooks/email.md).
 */
@ConfigurationProperties("notifications")
@Validated
record NotificationProperties(
        @NotNull URI webAppUrl, @DefaultValue Email email) {

    /**
     * @param webhookSecret the signing secret of the provider's bounce and complaint webhook ({@code
     *     NOTIFICATIONS_EMAIL_WEBHOOK_SECRET}, Resend's {@code whsec_…}); without one every call to it is refused
     */
    record Email(@NotBlank String from, @Nullable String webhookSecret) {}

    /** The domain the sender's address is on: {@code mail.idleo.in} in {@code IdleO <notifications@mail.idleo.in>}. */
    String senderDomain() {
        var from = email.from().strip();
        var address = from.contains("<") ? from.substring(from.indexOf('<') + 1, from.lastIndexOf('>')) : from;
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** The address of a path in the web app: {@code /session/?id=1} becomes {@code https://app/session/?id=1}. */
    String absolute(String path) {
        var base = webAppUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}
