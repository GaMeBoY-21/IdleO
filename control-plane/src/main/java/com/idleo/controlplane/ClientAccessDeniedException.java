package com.idleo.controlplane;

import org.springframework.security.access.AccessDeniedException;

/**
 * A 403 whose message is written for the client, such as "This account is suspended.", with its {@link Problem}
 * type. {@link ProblemDetailsAdvice} returns both; every other access denial gets a generic {@code access-denied}.
 */
public class ClientAccessDeniedException extends AccessDeniedException {

    private static final long serialVersionUID = 1L;

    private final Problem problem;

    public ClientAccessDeniedException(Problem problem, String clientMessage) {
        super(clientMessage);
        if (problem.status().value() != 403) {
            throw new IllegalArgumentException(problem + " isn't a 403");
        }
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
