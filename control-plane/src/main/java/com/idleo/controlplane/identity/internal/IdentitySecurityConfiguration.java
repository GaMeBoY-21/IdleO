package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
import com.idleo.controlplane.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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

    /**
     * The calls that need no sign-in: starting a CLI sign-in and polling it (ADR 0025). The poll's own token is its
     * credential.
     */
    @Bean
    RequestMatcher signedOutCalls() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/v1/cli-logins"),
                paths.matcher(HttpMethod.POST, "/v1/cli-logins/{id}/token"));
    }

    /**
     * The one place a bearer value is decided (ADR 0025): one starting {@code idleo_} is checked as an API key,
     * anything else as an ID token, with one decoder per trusted issuer, each with its own audience.
     */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> bearerAuthenticationManagerResolver(
            IdTokenAuthentication idTokens, ApiKeyAuthentication apiKeys) {
        var tokens = idTokens.resolver();
        return request -> isApiKey(request) ? apiKeys : tokens.resolve(request);
    }

    static boolean isApiKey(HttpServletRequest request) {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null
                && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                && ApiKeySecrets.looksLikeKey(header.substring(BEARER.length()));
    }

    private static final String BEARER = "Bearer ";

    /**
     * Grants access only to an active user. Anonymous requests are denied (and get a 401). A valid
     * token whose account is suspended, or whose email isn't verified, gets a 403 that says why. A waiting
     * user (no invite code yet) may only read and rename their own account and activate it; everything else is
     * a 403 {@code invite-required}. A request made with an API key is then held to {@link KeyRules}: some calls need a
     * browser's sign-in, and a read key only reads.
     */
    @Bean
    AuthorizationManager<RequestAuthorizationContext> activeUserAuthorization() {
        return (authentication, context) -> {
            if (authentication.get() instanceof IdentityAuthenticationToken token) {
                token.denial().ifPresent(denial -> {
                    throw new ClientAccessDeniedException(denial.problem(), denial.message());
                });
                var request = context.getRequest();
                if (token.isWaiting() && !isOwnAccountCall(request)) {
                    throw new ClientAccessDeniedException(Problem.INVITE_REQUIRED, INVITE_REQUIRED);
                }
                token.key()
                        .flatMap(key -> KeyRules.refusal(request.getMethod(), request.getRequestURI(), key))
                        .ifPresent(denial -> {
                            throw new ClientAccessDeniedException(denial.problem(), denial.message());
                        });
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(false);
        };
    }
}
