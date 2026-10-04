package com.idleo.controlplane.hosts;

/**
 * Trades a one-time enrolment code and a certificate signing request for a 30-day client
 * certificate from our own certificate authority (ADR 0010). The transport (gRPC, in the agent
 * gateway) calls this; only the hosts module touches codes, keys and certificate records.
 */
public interface HostEnrolment {

    /**
     * Issues a host certificate for the code's host. The code is used up in the same transaction that
     * records the certificate. Only the CSR's public key is used: every name or extension it asks for
     * is ignored, and the certificate always names the code's host.
     *
     * @param enrolmentCode the code as typed by the host, in any case, with or without hyphens
     * @param csrDer a DER-encoded PKCS#10 request for an EC P-256 key, signed by that key
     * @throws InvalidEnrolmentCodeException if the code is unknown, malformed, used or expired, with
     *     no way to tell which
     * @throws InvalidCertificateRequestException if the CSR can't be parsed, isn't P-256, or its
     *     signature doesn't verify
     */
    EnrolledCertificate enrol(String enrolmentCode, byte[] csrDer);
}
