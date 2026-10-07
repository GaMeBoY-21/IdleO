package com.idleo.controlplane;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private ApiProblem(Problem problem, String detail, Map<String, Object> properties) {
        super(detail, null, false, false);
        this.problem = Objects.requireNonNull(problem);
        this.properties = Map.copyOf(properties);
    }

    /** The error {@code problem}, with {@code detail} saying what happened, for the client. */
    public static ApiProblem of(Problem problem, String detail) {
        return new ApiProblem(problem, detail, Map.of());
    }

    /** A {@link Problem#VALIDATION_FAILED} naming each invalid field. */
    public static ApiProblem invalid(String detail, List<FieldError> errors) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("errors", List.copyOf(errors));
        return new ApiProblem(Problem.VALIDATION_FAILED, detail, properties);
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
