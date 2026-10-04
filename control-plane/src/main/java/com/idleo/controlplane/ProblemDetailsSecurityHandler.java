package com.idleo.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Turns Spring Security's 401 and 403 into RFC 9457 problem details. It hands both exceptions to
 * Spring MVC's exception resolver, so {@link ProblemDetailsAdvice} renders them exactly like every
 * other error: one implementation for all problem responses.
 *
 * <p>A 401 also carries {@code WWW-Authenticate} as RFC 6750 requires: {@code Bearer} when no token
 * was sent, and {@code Bearer error="invalid_token"} when a token was sent and rejected. The error
 * description is left out on purpose, so a rejection never explains which check failed.
 */
@Component
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemDetailsSecurityHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(exception));
        resolver.resolveException(request, response, null, exception);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        resolver.resolveException(request, response, null, exception);
    }

    private static String wwwAuthenticate(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException rejected) {
            return "Bearer error=\"" + rejected.getError().getErrorCode() + "\"";
        }
        return "Bearer";
    }
}
