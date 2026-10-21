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
     * credential, the public pages' availability (ADR 0029), the Windows app's release and its setup program's
     * measurements (ADR 0034), the email provider's signed webhook and real visits' vitals (ADR 0036).
     */
    @Bean
    RequestMatcher signedOutCalls() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/v1/cli-logins"),
                paths.matcher(HttpMethod.POST, "/v1/cli-logins/{id}/token"),
                // A host's agent asking to be linked, and polling for its ticket (ADR 0032): its poll token is its
                // proof.
                paths.matcher(HttpMethod.POST, "/v1/host-links"),
                paths.matcher(HttpMethod.POST, "/v1/host-links/{id}/ticket"),
                paths.matcher(HttpMethod.GET, "/v1/public/availability"),
                // The Windows app's latest release and its download, for the setup app and the Download buttons.
                paths.matcher(HttpMethod.GET, "/v1/agent-releases/latest"),
                paths.matcher(HttpMethod.GET, "/v1/agent-releases/latest/setup"),
                // The setup app's measurements, under an install id it makes, before anyone has signed in.
                paths.matcher(HttpMethod.POST, "/v1/host-installs/{installId}/events"),
                // The email provider's bounces and complaints (ADR 0036): its signature is its proof.
                paths.matcher(HttpMethod.POST, "/v1/email-events/resend"),
                // What real visits meet, sent by the pages with navigator.sendBeacon, which carries no token (ADR
                // 0036).
                paths.matcher(HttpMethod.POST, "/v1/vitals"));
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
