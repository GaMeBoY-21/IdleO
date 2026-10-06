package com.idleo.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Deny by default. Only the two health probes are open. Every other request, including every other
 * actuator endpoint, needs a valid ID token from a trusted issuer, belonging to an active user with a
 * verified email. The identity module provides both the token verification and the access rule, as
 * plain Spring Security beans, so this shared code never depends on it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class SecurityConfiguration {

    static final String[] OPEN_PATHS = {"/actuator/health/liveness", "/actuator/health/readiness"};

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> idTokens,
            AuthorizationManager<RequestAuthorizationContext> activeUser,
            ProblemDetailsSecurityHandler problems) {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(OPEN_PATHS)
                        .permitAll()
                        .anyRequest()
                        .access(activeUser))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(idTokens)
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                // A stateless API: no cookies and no server sessions, so there is no
                // cookie-based session for CSRF to hijack.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
