package com.idleo.controlplane;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An error for the client: one entry of the {@link Problem} catalog and a {@code detail} written for this case
 * (ADR 0019). The only way the API raises an error; {@code ProblemDetailsAdvice} turns it into an RFC 9457 problem
 * detail, and a test fails if one is built any other way.
 */
public final class ApiProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One invalid field, for {@link Problem#VALIDATION_FAILED}: its name, such as {@code params.epochs}, and why. */
    public record FieldError(String field, String detail) {}

    private final Problem problem;
    private final transient Map<String, Object> properties;
    private final @Nullable Duration retryAfter;

    private ApiProblem(Problem problem, String detail, Map<String, Object> properties, @Nullable Duration retryAfter) {
        super(detail, null, false, false);
        this.problem = Objects.requireNonNull(problem);
        this.properties = Map.copyOf(properties);
        this.retryAfter = retryAfter;
    }

    private ApiProblem(Problem problem, String detail, Map<String, Object> properties) {
        this(problem, detail, properties, null);
    }

    /**
     * The same problem, telling the client when to try again: a {@code Retry-After} header, in whole seconds,
     * rounded up and at least one (RFC 9110 section 10.2.3). For the limits' 429s.
     */
    public ApiProblem retryAfter(Duration wait) {
        return new ApiProblem(problem, detail(), properties, wait);
    }

    /** When the client may try again, if the problem says. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** The error {@code problem}, with {@code detail} saying what happened, for the client. */
    public static ApiProblem of(Problem problem, String detail) {
        return new ApiProblem(problem, detail, Map.of());
    }

    /**
     * The error {@code problem}, naming the fields it's about in {@code errors}, as validation errors do, so a client
     * can point at them (an input such as {@code inputs.model}).
     */
    public static ApiProblem of(Problem problem, String detail, List<FieldError> errors) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("errors", List.copyOf(errors));
        return new ApiProblem(problem, detail, properties);
    }

    /** A {@link Problem#VALIDATION_FAILED} naming each invalid field. */
    public static ApiProblem invalid(String detail, List<FieldError> errors) {
        return of(Problem.VALIDATION_FAILED, detail, errors);
    }

    /** A {@link Problem#VALIDATION_FAILED} for one field. */
    public static ApiProblem invalid(String field, String detail) {
        return invalid("The request has invalid fields.", List.of(new FieldError(field, detail)));
    }

    public Problem problem() {
        return problem;
    }

    public String detail() {
        return Objects.requireNonNullElse(getMessage(), problem.title());
    }

    /** Extra members of the problem detail, such as {@code errors}. */
    public Map<String, Object> properties() {
        return properties;
    }
}
