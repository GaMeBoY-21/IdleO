package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * Authenticates connecting agents by their client certificate, and renews certificates near expiry
 * (ADR 0011). Only the hosts module reads or issues certificates.
 */
public interface HostCertificates {

    /**
     * Which host a client certificate belongs to. It is valid only if its row exists and isn't
     * revoked, now (from the injected clock) is inside its validity, and the host isn't suspended.
     *
     * @param fingerprintSha256 SHA-256 of the certificate's DER encoding
     */
    CertificateCheck authenticate(byte[] fingerprintSha256);

    /**
     * Issues a new 30-day certificate for the host, if the calling certificate has 10 days or fewer
     * left. The CSR rules are enrolment's: EC P-256 only, a valid signature, and only the public key
     * is used. The calling certificate stays valid until it expires.
     *
     * @throws RenewalNotOpenException if the calling certificate has more than 10 days left
     * @throws InvalidCertificateRequestException if the CSR is refused
     */
    EnrolledCertificate renew(UUID hostId, byte[] currentFingerprintSha256, byte[] csrDer);

    /** The result of {@link #authenticate}. */
    sealed interface CertificateCheck {

        /** A valid certificate for an active host. */
        record Valid(UUID hostId) implements CertificateCheck {}

        /** Unknown, revoked, not yet valid or expired; deliberately one answer for all four. */
        record Invalid() implements CertificateCheck {}

        /** A valid certificate whose host is suspended. */
        record HostSuspended(UUID hostId) implements CertificateCheck {}
    }
}
