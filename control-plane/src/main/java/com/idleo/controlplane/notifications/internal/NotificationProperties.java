package com.idleo.controlplane.notifications.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
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

    record Email(@NotBlank String from) {}

    /** The address of a path in the web app: {@code /session/?id=1} becomes {@code https://app/session/?id=1}. */
    String absolute(String path) {
        var base = webAppUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}
