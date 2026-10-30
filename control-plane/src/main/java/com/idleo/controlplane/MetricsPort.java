package com.idleo.controlplane;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The meters in Prometheus's format (ADR 0046): {@code GET /metrics}, answered only on {@code metrics.prometheus.port},
 * a port of its own that no route leads to (the public port answers it 404, and that port answers nothing else). Only
 * in a cluster (application-cluster.yaml), for the in-cluster Prometheus that Flagger judges a canary by; the chart's
 * network policy lets only that Prometheus reach the port. The meters leave through OTLP as before (ADR 0036): this is
 * a second view of the same registry, never a second set of meters. Its scrapes aren't requests the meters count.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnProperty("metrics.prometheus.port")
class MetricsPort {

    static final String PATH = "/metrics";

    /** The meters' port, beside the public one. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> metricsConnector(
            @Value("${metrics.prometheus.port}") int port) {
        return factory -> {
            var connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            factory.addAdditionalConnectors(connector);
        };
    }

    @Bean
    // Before the application's chains: on this port nothing else is answered.
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain metricsSecurityFilterChain(
            HttpSecurity http,
            @Value("${metrics.prometheus.port}") int port,
            PrometheusMeterRegistry registry,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return http.securityMatcher(request -> request.getLocalPort() == port || PATH.equals(request.getRequestURI()))
                .addFilterBefore(new Gate(port, registry, resolver), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /** A scrape isn't a request of the API's: it adds nothing to http.server.requests, which canaries are judged by. */
    @Bean
    ObservationPredicate notMetricsScrapes(@Value("${metrics.prometheus.port}") int port) {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getLocalPort() == port);
    }

    /** Answers {@code GET /metrics} on the meters' port with the registry's scrape, and everything else 404. */
    static final class Gate extends OncePerRequestFilter {

        private final int port;
        private final PrometheusMeterRegistry registry;
        private final HandlerExceptionResolver resolver;

        Gate(int port, PrometheusMeterRegistry registry, HandlerExceptionResolver resolver) {
            this.port = port;
            this.registry = registry;
            this.resolver = resolver;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException {
            if (request.getLocalPort() != port
                    || !PATH.equals(request.getRequestURI())
                    || !"GET".equals(request.getMethod())) {
                resolver.resolveException(
                        request, response, null, ApiProblem.of(Problem.NOT_FOUND, "There's nothing here."));
                return;
            }
            response.setContentType("text/plain; version=0.0.4; charset=utf-8");
            response.getOutputStream().write(registry.scrape().getBytes(StandardCharsets.UTF_8));
        }
    }
}
