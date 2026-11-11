package com.idleo.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny by default. Only the two health probes are open, and the calls the identity module names as needing no
 * sign-in ({@code signedOutCalls}: starting and polling a CLI sign-in). Every other request, including every other
 * actuator endpoint, needs a valid ID token from a trusted issuer or an API key (ADR 0025), belonging to an
 * active user. The identity module provides both the token verification and the access rule, as
 * plain Spring Security beans, so this shared code never depends on it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SecurityConfiguration {

    static final String[] OPEN_PATHS = {"/actuator/health/liveness", "/actuator/health/readiness"};

    /**
     * The web calls the API from its own origins (ADR 0018, ADR 0058, {@link WebOrigins}): only they may, and only with a
     * bearer token, never cookies. {@code /v1} takes the renter app's, the host site's, the website's and the admin
     * site's; {@code /admin/v1} only the admin site's. None by default; {@code localhost} only in the local profile,
     * which the production guard enforces.
     */
    @Bean
    CorsConfigurationSource webOrigins(Environment environment) {
        var source = new UrlBasedCorsConfigurationSource();
        var origins = WebOrigins.of(environment);
        if (!origins.all().isEmpty()) {
            var cors = new CorsConfiguration();
            cors.setAllowedOrigins(origins.all());
            cors.setAllowedMethods(METHODS);
            cors.setAllowedHeaders(List.of(
                    HttpHeaders.AUTHORIZATION,
                    HttpHeaders.CONTENT_TYPE,
                    HttpHeaders.IF_MATCH,
                    IdempotentRequests.HEADER));
            cors.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION));
            cors.setAllowCredentials(false);
            cors.setMaxAge(MAX_AGE);
            source.registerCorsConfiguration("/v1/**", cors);
        }
        if (!origins.admin().isEmpty()) {
            // The admin console (ADR 0027), on the admin site alone (ADR 0058), with its admin session's header.
            var admin = new CorsConfiguration();
            admin.setAllowedOrigins(origins.admin());
            admin.setAllowedMethods(METHODS);
            admin.setAllowedHeaders(List.of(
                    HttpHeaders.AUTHORIZATION,
                    HttpHeaders.CONTENT_TYPE,
                    HttpHeaders.IF_MATCH,
                    IdempotentRequests.HEADER,
                    "Admin-Session"));
            // An approved export's name and kind, which the console saves it under.
            admin.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.CONTENT_DISPOSITION));
            admin.setAllowCredentials(false);
            admin.setMaxAge(MAX_AGE);
            source.registerCorsConfiguration("/admin/v1/**", admin);
        }
        return source;
    }

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Duration MAX_AGE = Duration.ofHours(1);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> bearers,
            AuthorizationManager<RequestAuthorizationContext> activeUser,
            ProblemDetailsSecurityHandler problems,
            CorsConfigurationSource webOrigins,
            @Qualifier("signedOutCalls") RequestMatcher signedOutCalls) {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(OPEN_PATHS)
                        .permitAll()
                        .requestMatchers(signedOutCalls)
                        .permitAll()
                        .anyRequest()
                        .access(activeUser))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(bearers)
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                // A stateless API: no cookies and no server sessions, so there is no
                // cookie-based session for CSRF to hijack.
                .cors(cors -> cors.configurationSource(webOrigins))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
