package com.idleo.controlplane.identity.internal;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The admin console's second factor and sessions (ADR 0027).
 *
 * <ul>
 *   <li>{@code identity.admin.totp-key} ({@code IDENTITY_ADMIN_TOTP_KEY}): the AES-256 key that encrypts every
 *       admin's TOTP secret, 32 bytes in base64, or a {@code file:} location holding that. Production reads it from
 *       the environment; locally it's {@code private/dev-ca/admin-totp.key}, made by {@code scripts/dev-ca.sh}.
 *       Changing it makes every enrolment unreadable, so each admin must be granted again.
 *   <li>{@code session-ttl}: an admin session lasts this long after its last use (30 minutes), and never longer
 *       than {@code session-max} (8 hours) from when it began.
 *   <li>{@code totp-issuer}: the name an authenticator app shows beside the code.
 * </ul>
 */
@ConfigurationProperties("identity.admin")
@Validated
record AdminProperties(
        @NotBlank String totpKey,
        @DefaultValue("30m") Duration sessionTtl,
        @DefaultValue("8h") Duration sessionMax,
        @DefaultValue("IdleO") String totpIssuer) {}
