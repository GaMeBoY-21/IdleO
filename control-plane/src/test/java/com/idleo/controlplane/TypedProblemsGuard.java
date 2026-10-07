package com.idleo.controlplane;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every problem detail any test produces carries a type from the catalog (ADR 0019): this filter wraps every request
 * in every {@link ControlPlaneTest}, outside security, and fails the test that got a problem detail without one, or
 * with {@code about:blank}, or whose status isn't its type's. Event streams pass straight through.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TypedProblemsGuard {

    static final Set<String> TYPES = Arrays.stream(Problem.values())
            .map(problem -> problem.type().toString())
            .collect(Collectors.toUnmodifiableSet());

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> typedProblems() {
        var json = JsonMapper.builder().build();
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getRequestURI().endsWith("/events")) {
                    chain.doFilter(request, response);
                    return;
                }
                var wrapped = new ContentCachingResponseWrapper(response);
                chain.doFilter(request, wrapped);
                var contentType = wrapped.getContentType();
                if (contentType != null && contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)) {
                    var body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                    var problem = json.readTree(body);
                    var type = problem.path("type").asString("");
                    var expected = Arrays.stream(Problem.values())
                            .filter(entry -> entry.type().toString().equals(type))
                            .findFirst();
                    if (expected.isEmpty()) {
                        throw new AssertionError("A problem detail without a catalog type: " + request.getMethod() + " "
                                + request.getRequestURI() + " -> " + body);
                    }
                    if (expected.get().status().value() != wrapped.getStatus() && wrapped.getStatus() < 500) {
                        throw new AssertionError("Status " + wrapped.getStatus() + " for " + type + ": " + body);
                    }
                }
                wrapped.copyBodyToResponse();
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
