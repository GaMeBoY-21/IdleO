package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * What the application's security configuration needs from the identity module, as plain Spring
 * Security beans, so the shared security code never depends on this module.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class IdentitySecurityConfiguration {

    /** Verifies ID tokens: one decoder per trusted issuer, each with its own audience. */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> idTokenAuthenticationManagerResolver(
            IdTokenAuthentication idTokens) {
        return idTokens.resolver();
    }

    /**
     * Grants access only to an active user. Anonymous requests are denied (and get a 401). A valid
     * token whose account is suspended, or whose email isn't verified, gets a 403 that says why.
     */
    @Bean
    AuthorizationManager<RequestAuthorizationContext> activeUserAuthorization() {
        return (authentication, context) -> {
            if (authentication.get() instanceof IdentityAuthenticationToken token) {
                token.denial().ifPresent(denial -> {
                    throw new ClientAccessDeniedException(denial.problem(), denial.message());
                });
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(false);
        };
    }
}
