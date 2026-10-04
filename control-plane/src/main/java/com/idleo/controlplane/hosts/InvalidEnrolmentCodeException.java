package com.idleo.controlplane.hosts;

/**
 * The enrolment code can't be used. Deliberately one exception, with one message, for an unknown,
 * malformed, used or expired code, so a caller can't tell which.
 */
public class InvalidEnrolmentCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEnrolmentCodeException() {
        super("The enrolment code is invalid, already used or expired.");
    }
}
