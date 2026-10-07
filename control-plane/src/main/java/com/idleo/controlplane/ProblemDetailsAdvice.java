package com.idleo.controlplane;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error the control plane returns is an RFC 9457 problem detail ({@code application/problem+json}) whose
 * {@code type} is an entry of the {@link Problem} catalog (ADR 0019). The API's own errors arrive as {@link
 * ApiProblem}; Spring MVC's, such as malformed JSON, 404 and 405, come from {@link ResponseEntityExceptionHandler}
 * and keep their status, with the catalog's type and title; security's 401 and 403 arrive through {@link
 * ProblemDetailsSecurityHandler}; anything unexpected becomes a 500 {@code internal-error} that never reveals the
 * exception's message or stack trace. Those are logged, not returned. This is the one place a problem detail is
 * built.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> problem(ApiProblem exception) {
        var problem = detail(exception.problem(), exception.problem().status(), exception.detail());
        exception.properties().forEach(problem::setProperty);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException exception) {
        var detail = exception instanceof OAuth2AuthenticationException
                ? "The bearer token is invalid or has expired."
                : "Authentication is required.";
        return detail(Problem.UNAUTHENTICATED, Problem.UNAUTHENTICATED.status(), detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        // Only a ClientAccessDeniedException carries a message written for the client.
        if (exception instanceof ClientAccessDeniedException forClient) {
            return detail(forClient.problem(), forClient.problem().status(), forClient.getMessage());
        }
        return detail(Problem.ACCESS_DENIED, Problem.ACCESS_DENIED.status(), "Access is denied.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return internalError();
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

    /**
     * Every error Spring MVC handles gets the catalog's type and title for what went wrong, keeping the status
     * Spring chose. Server errors keep nothing of the exception.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Spring MVC failed a request", exception);
            var problem = internalError();
            problem.setStatus(status.value());
            return super.handleExceptionInternal(exception, problem, headers, status, request);
        }
        var catalog = forSpring(exception, status);
        var problem = body instanceof ProblemDetail given ? given : ProblemDetail.forStatus(status);
        problem.setType(catalog.type());
        problem.setTitle(catalog.title());
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** The catalog entry for one of Spring MVC's own client errors. */
    static Problem forSpring(Exception exception, HttpStatusCode status) {
        return switch (exception) {
            case MethodArgumentNotValidException _ -> Problem.VALIDATION_FAILED;
            case HandlerMethodValidationException _ -> Problem.VALIDATION_FAILED;
            case HttpRequestMethodNotSupportedException _ -> Problem.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotAcceptableException _ -> Problem.NOT_ACCEPTABLE;
            case HttpMediaTypeNotSupportedException _ -> Problem.UNSUPPORTED_MEDIA_TYPE;
            case NoResourceFoundException _ -> Problem.NOT_FOUND;
            case NoHandlerFoundException _ -> Problem.NOT_FOUND;
            case HttpMessageNotReadableException _ -> Problem.MALFORMED_REQUEST;
            case ServletRequestBindingException _ -> Problem.MALFORMED_REQUEST;
            case TypeMismatchException _ -> Problem.MALFORMED_REQUEST;
            default -> byStatus(status);
        };
    }

    /** Anything else Spring MVC reports: the entry with the same status, or a malformed request. */
    private static Problem byStatus(HttpStatusCode status) {
        return List.of(
                        Problem.NOT_FOUND,
                        Problem.METHOD_NOT_ALLOWED,
                        Problem.NOT_ACCEPTABLE,
                        Problem.UNSUPPORTED_MEDIA_TYPE)
                .stream()
                .filter(problem -> problem.status().value() == status.value())
                .findFirst()
                .orElse(Problem.MALFORMED_REQUEST);
    }

    private static ProblemDetail internalError() {
        return detail(
                Problem.INTERNAL_ERROR,
                Problem.INTERNAL_ERROR.status(),
                "Something failed on our side. Try again; if it keeps failing, tell us when it happened.");
    }

    private static ProblemDetail detail(Problem problem, HttpStatusCode status, String detail) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(problem.type());
        body.setTitle(problem.title());
        return body;
    }
}
