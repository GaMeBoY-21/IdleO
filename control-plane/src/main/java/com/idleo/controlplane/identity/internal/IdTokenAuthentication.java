package com.idleo.controlplane.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.stereotype.Component;

/**
 * One decoder per trusted issuer, each with its own audience check. Spring's {@code
 * JwtIssuerAuthenticationManagerResolver.fromTrustedIssuers(...)} would check the issuer but not the
 * audience, so a token minted for another project on the same issuer would pass. An unknown issuer
 * gets no decoder and is rejected.
 *
 * <p>Each decoder finds the issuer's signing keys through standard OIDC discovery ({@code
 * <issuer>/.well-known/openid-configuration}), lazily on first use, so startup never waits on the
 * provider. No provider SDK is involved.
 */
@Component
@EnableConfigurationProperties(IdentityProperties.class)
class IdTokenAuthentication {

    private final JwtIssuerAuthenticationManagerResolver resolver;

    IdTokenAuthentication(IdentityProperties properties, SignInAuthenticationConverter converter, Clock clock) {
        Map<String, AuthenticationManager> managers = properties.issuers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        IdentityProperties.Issuer::issuerUri, issuer -> manager(issuer, converter, clock)));
        this.resolver = new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    AuthenticationManagerResolver<HttpServletRequest> resolver() {
        return resolver;
    }

    private static AuthenticationManager manager(
            IdentityProperties.Issuer issuer, SignInAuthenticationConverter converter, Clock clock) {
        JwtDecoder decoder = new SupplierJwtDecoder(() -> {
            var nimbus = NimbusJwtDecoder.withIssuerLocation(issuer.issuerUri()).build();
            nimbus.setJwtValidator(IdTokenValidators.forIssuer(issuer.issuerUri(), issuer.audience(), clock));
            return nimbus;
        });
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return new ProviderManager(provider);
    }
}
