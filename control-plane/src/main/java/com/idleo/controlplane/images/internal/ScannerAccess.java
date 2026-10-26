package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The scanner's way in (ADR 0042): {@code /internal/v1/scanner/**}, answered only on {@code images.scanner.port}, a port
 * of its own that the load balancer never routes (the public port answers it 404, and that port answers nothing else),
 * and only with the scanner's bearer token ({@code images.scanner.token}), compared in constant time. Without a token
 * configured, nothing is answered there. No person's sign-in counts here, and the scanner's token counts nowhere else.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class ScannerAccess {

    static final String PATH = "/internal/v1/scanner/";

    /** The internal port, beside the public one. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> scannerPort(ImageProperties properties) {
        return factory -> {
            var connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(properties.scanner().port());
            factory.addAdditionalConnectors(connector);
        };
    }

    @Bean
    // First of all chains: on the scanner's port nothing else is answered, not even the admin console's paths.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain scannerSecurityFilterChain(HttpSecurity http, ImageProperties properties) {
        int port = properties.scanner().port();
        var token = properties.scanner().token();
        return http.securityMatcher(request -> request.getLocalPort() == port
                        || request.getRequestURI().startsWith("/internal/"))
                .addFilterBefore(new Gate(port, token == null ? "" : token), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** Refuses everything but the scanner's own calls, on its own port, with its token. */
    static final class Gate extends OncePerRequestFilter {

        private final int port;
        private final byte[] token;

        Gate(int port, String token) {
            this.port = port;
            this.token = token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var path = request.getRequestURI();
            if (request.getLocalPort() != port || !path.startsWith(PATH) || token.length == 0) {
                problem(response, Problem.NOT_FOUND);
                return;
            }
            var header = request.getHeader("Authorization");
            var given = header != null && header.startsWith("Bearer ")
                    ? header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            if (!MessageDigest.isEqual(given, token)) {
                problem(response, Problem.UNAUTHENTICATED);
                return;
            }
            chain.doFilter(request, response);
        }

        private static void problem(HttpServletResponse response, Problem problem) throws IOException {
            response.setStatus(problem.status().value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter()
                    .write("{\"type\":\"" + problem.type() + "\",\"title\":\"" + problem.title() + "\",\"status\":"
                            + problem.status().value() + "}");
        }
    }
}
