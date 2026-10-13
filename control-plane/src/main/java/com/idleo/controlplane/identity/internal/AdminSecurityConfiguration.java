package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The admin console's own security chain (ADR 0027), for {@code /admin/v1/**} only, ahead of the API's.
 *
 * <ul>
 *   <li>Only ID tokens: a bearer value that is an API key never gets through.
 *   <li>The caller must be an active admin whose access isn't locked. The second-factor calls ({@link
 *       #SECOND_FACTOR_CALLS}) need nothing more; every other call also needs a live admin session in the {@value
 *       AdminSessions#HEADER} header, made by this same sign-in identity, and renews it.
 *   <li>Anything short of that is a 404 exactly like a path that doesn't exist, never a 401 or a 403, so nobody
 *       can tell which admin paths exist.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(AdminProperties.class)
class AdminSecurityConfiguration {

    static final String PATHS = "/admin/v1/**";

    /** What an admin may call before an admin session exists: its status, starting one, and enrolment. */
    static final Set<String> SECOND_FACTOR_CALLS =
            Set.of("/admin/v1/session", "/admin/v1/second-factor", "/admin/v1/second-factor/confirm");

    @Bean
    TotpSecrets totpSecrets(AdminProperties properties) {
        return new TotpSecrets(properties.totpKey(), new SecureRandom());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            IdTokenAuthentication idTokens,
            AdminSecondFactor admins,
            AdminSessions sessions,
            CorsConfigurationSource webOrigins,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        AuthenticationManager refuseKeys = authentication -> {
            throw new InvalidBearerTokenException("API keys never reach admin paths");
        };
        var tokens = idTokens.resolver();
        AuthenticationManagerResolver<HttpServletRequest> idTokensOnly =
                request -> IdentitySecurityConfiguration.isApiKey(request) ? refuseKeys : tokens.resolve(request);
        var notFound = new NotFound(resolver);
        return http.securityMatcher(PATHS)
                .authorizeHttpRequests(requests -> requests.anyRequest().access(access(admins, sessions)))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(idTokensOnly)
                        .authenticationEntryPoint((request, response, e) -> notFound.send(request, response))
                        .accessDeniedHandler((request, response, e) -> notFound.send(request, response)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, e) -> notFound.send(request, response))
                        .accessDeniedHandler((request, response, e) -> notFound.send(request, response)))
                .cors(cors -> cors.configurationSource(webOrigins))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> access(
            AdminSecondFactor admins, AdminSessions sessions) {
        return (authentication, context) -> {
            if (!(authentication.get() instanceof IdentityAuthenticationToken token)
                    || token.denial().isPresent()
                    || token.isWaiting()
                    || token.key().isPresent()
                    || token.identityId().isEmpty()
                    || !(token.getPrincipal() instanceof CurrentUser user)) {
                return new AuthorizationDecision(false);
            }
            var admin = admins.admin(user.id());
            if (admin.isEmpty()) {
                return new AuthorizationDecision(false);
            }
            var request = context.getRequest();
            if (SECOND_FACTOR_CALLS.contains(request.getRequestURI())) {
                return new AuthorizationDecision(true);
            }
            var session = request.getHeader(AdminSessions.HEADER);
            return new AuthorizationDecision(admin.get().enrolled()
                    && session != null
                    && sessions.use(user.id(), token.identityId().get(), session)
                            .isPresent());
        };
    }

    /** The same 404 problem as a path that doesn't exist, through the one renderer of every problem. */
    private record NotFound(HandlerExceptionResolver resolver) {

        void send(HttpServletRequest request, HttpServletResponse response) {
            var path = request.getRequestURI();
            resolver.resolveException(
                    request,
                    response,
                    null,
                    new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()), path, path.substring(1)));
        }
    }
}
