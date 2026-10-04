package com.idleo.controlplane;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny by default. Only the two health probes are open; every other request, including every other
 * actuator endpoint, needs an authenticated caller. There is no way to authenticate yet: login and
 * tokens arrive with the identity module.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String[] OPEN_PATHS = {"/actuator/health/liveness", "/actuator/health/readiness"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsSecurityHandler problems) {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(OPEN_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                // A stateless API: no cookies and no server sessions, so there is no
                // cookie-based session for CSRF to hijack.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
