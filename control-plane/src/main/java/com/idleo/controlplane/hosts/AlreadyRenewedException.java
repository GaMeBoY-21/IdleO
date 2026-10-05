package com.idleo.controlplane.hosts;

/** The calling certificate was already renewed; renewal works only from the host's newest certificate. */
public class AlreadyRenewedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlreadyRenewedException() {
        super("This certificate was already renewed; use the newer certificate.");
    }
}
