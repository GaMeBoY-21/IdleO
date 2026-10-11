package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
import com.idleo.controlplane.Problem;
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

    static final String INVITE_REQUIRED = "Enter an invite code to use IdleO.";

    /** The only calls a waiting user may make: {@code GET} and {@code PATCH /v1/me}, {@code POST /v1/me/activate}. */
    private static boolean isOwnAccountCall(HttpServletRequest request) {
        var method = request.getMethod();
        var path = request.getRequestURI();
        return switch (path) {
            case "/v1/me" -> method.equals("GET") || method.equals("PATCH");
            case "/v1/me/activate" -> method.equals("POST");
            default -> false;
        };
    }

    /** Verifies ID tokens: one decoder per trusted issuer, each with its own audience. */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> idTokenAuthenticationManagerResolver(
            IdTokenAuthentication idTokens) {
        return idTokens.resolver();
    }

    /**
     * Grants access only to an active user. Anonymous requests are denied (and get a 401). A valid
     * token whose account is suspended, or whose email isn't verified, gets a 403 that says why. A waiting
     * user (no invite code yet) may only read and rename their own account and activate it; everything else is
     * a 403 {@code invite-required}.
     */
    @Bean
    AuthorizationManager<RequestAuthorizationContext> activeUserAuthorization() {
        return (authentication, context) -> {
            if (authentication.get() instanceof IdentityAuthenticationToken token) {
                token.denial().ifPresent(denial -> {
                    throw new ClientAccessDeniedException(denial.problem(), denial.message());
                });
                if (token.isWaiting() && !isOwnAccountCall(context.getRequest())) {
                    throw new ClientAccessDeniedException(Problem.INVITE_REQUIRED, INVITE_REQUIRED);
                }
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(false);
        };
    }
}
