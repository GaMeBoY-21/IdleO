package com.idleo.controlplane.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The trusted ID token issuers, each with its own audience, from the environment:
 * {@code IDENTITY_ISSUERS_0_ISSUERURI} and {@code IDENTITY_ISSUERS_0_AUDIENCE}, then {@code _1_} for a
 * second issuer during a provider move (ADR 0009). The application refuses to start without one.
 */
@ConfigurationProperties("identity")
@Validated
record IdentityProperties(@NotEmpty List<@Valid Issuer> issuers) {

    record Issuer(@NotBlank String issuerUri, @NotBlank String audience) {}
}
