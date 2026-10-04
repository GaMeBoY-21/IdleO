package com.idleo.controlplane.hosts;

/** The certificate signing request was refused. The message is safe to return to the caller. */
public class InvalidCertificateRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCertificateRequestException(String clientMessage) {
        super(clientMessage);
    }
}
