package com.idleo.controlplane;

import org.springframework.security.access.AccessDeniedException;

/**
 * A 403 (or, for someone new while sign-ups are paused, the 503 {@code paused}) whose message is written for the client, such as "This account is suspended.", with its {@link Problem}
 * type. {@link ProblemDetailsAdvice} returns both; every other access denial gets a generic {@code access-denied}.
 */
public class ClientAccessDeniedException extends AccessDeniedException {

    private static final long serialVersionUID = 1L;

    private final Problem problem;

    public ClientAccessDeniedException(Problem problem, String clientMessage) {
        super(clientMessage);
        // The one refusal at sign-in that isn't a 403: someone new while sign-ups are paused (ADR 0056).
        if (problem.status().value() != 403 && problem != Problem.PAUSED) {
            throw new IllegalArgumentException(problem + " isn't a 403");
        }
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
