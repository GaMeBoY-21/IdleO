package com.idleo.controlplane.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The trusted ID token issuers, each with its own audience, from the environment:
 * {@code IDENTITY_ISSUERS_0_ISSUERURI} and {@code IDENTITY_ISSUERS_0_AUDIENCE}, then {@code _1_} for a
 * second issuer during a provider move (ADR 0009). The application refuses to start without one.
 *
 * <p>{@code identity.invite-only} (default true; {@code IDENTITY_INVITE_ONLY}): a new account is waiting
 * until it enters an invite code (ADR 0024). False makes new accounts active at once; tests that aren't about
 * invites and the end-to-end run use it.
 *
 * <p>{@code identity.web-app-url} ({@code IDENTITY_WEB_APP_URL}): the web app, where a CLI sign-in is approved
 * ({@code /cli-login/}); the prod profile refuses one that is plain HTTP or on this machine. {@code
 * identity.cli-login}: how long a CLI sign-in lasts and how often the CLI may poll (ADR 0025, RFC 8628).
 */
@ConfigurationProperties("identity")
@Validated
record IdentityProperties(
        @NotEmpty List<@Valid Issuer> issuers,
        @DefaultValue("true") boolean inviteOnly,
        @NotNull URI webAppUrl,
        @DefaultValue CliLogin cliLogin) {

    record Issuer(@NotBlank String issuerUri, @NotBlank String audience) {}

    record CliLogin(
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("3s") Duration pollInterval) {}
}
