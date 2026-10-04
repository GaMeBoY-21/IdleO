package com.idleo.controlplane;

import org.springframework.security.access.AccessDeniedException;

/**
 * A 403 whose message is written for the client, such as "This account is suspended." {@link
 * ProblemDetailsAdvice} returns the message as the problem detail; every other access denial gets a
 * generic one.
 */
public class ClientAccessDeniedException extends AccessDeniedException {

    private static final long serialVersionUID = 1L;

    public ClientAccessDeniedException(String clientMessage) {
        super(clientMessage);
    }
}
