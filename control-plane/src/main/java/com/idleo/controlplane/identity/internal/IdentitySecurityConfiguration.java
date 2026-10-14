package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ClientAccessDeniedException;
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

    /**
     * The calls that need no sign-in: starting a CLI sign-in and polling it (ADR 0025), whose poll token is its own
     * credential, and the public pages' availability (ADR 0029).
     */
    @Bean
    RequestMatcher signedOutCalls() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/v1/cli-logins"),
                paths.matcher(HttpMethod.POST, "/v1/cli-logins/{id}/token"),
                paths.matcher(HttpMethod.GET, "/v1/public/availability"));
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
     * Grants access only to a signed-in user. Anonymous requests are denied (and get a 401). A valid token whose
     * account is suspended, or whose email isn't verified, gets a 403 that says why. Which calls a waiting user or an
     * API key may make depends on the call, so it is decided after routing, on the handler reached, by {@link
     * AccessRules}: never here, where only the path's spelling is known.
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
