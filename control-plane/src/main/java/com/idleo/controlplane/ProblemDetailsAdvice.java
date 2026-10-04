package com.idleo.controlplane;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error the control plane returns is an RFC 9457 problem detail ({@code
 * application/problem+json}). Spring MVC's own errors, such as 404 and 405, come from {@link
 * ResponseEntityExceptionHandler}; security's 401 and 403 arrive through {@link
 * ProblemDetailsSecurityHandler}; anything unexpected becomes a bare 500 that never reveals the
 * exception's message or stack trace. Those are logged, not returned.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException exception) {
        var detail = exception instanceof OAuth2AuthenticationException
                ? "The bearer token is invalid or has expired."
                : "Authentication is required.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        // Only a ClientAccessDeniedException carries a message written for the client.
        var detail = exception instanceof ClientAccessDeniedException forClient
                ? forClient.getMessage()
                : "Access is denied.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** A 400 for invalid fields names each field and what's wrong with it. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = exception.getBody();
        problem.setDetail("The request has invalid fields.");
        problem.setProperty(
                "errors",
                exception.getFieldErrors().stream()
                        .map(error -> Map.of(
                                "field",
                                error.getField(),
                                "detail",
                                Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                        .toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
