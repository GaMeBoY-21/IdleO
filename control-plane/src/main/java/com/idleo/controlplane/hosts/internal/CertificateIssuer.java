package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.EnrolledCertificate;
import com.idleo.controlplane.hosts.InvalidCertificateRequestException;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place host certificates are issued, for enrolment and renewal alike: the CSR rules, the
 * signature by our intermediate, and the row in hosts.host_certificates. Callers run it inside their
 * own transaction, so the certificate is recorded together with whatever authorised it.
 */
@Component
class CertificateIssuer {

    static final String P256_REQUIRED = "The certificate signing request must be for an EC P-256 key.";
    static final String UNREADABLE = "The certificate signing request is not a valid DER-encoded PKCS#10 request.";
    static final String BAD_SIGNATURE = "The certificate signing request's signature does not verify.";

    private final CertificateAuthority authority;
    private final JdbcClient jdbc;

    CertificateIssuer(CertificateAuthority authority, JdbcClient jdbc) {
        this.authority = authority;
        this.jdbc = jdbc;
    }

    /**
     * The CSR's public key, once its signature verifies and it is EC P-256. Its subject, names and
     * requested extensions are ignored on purpose: the certificate authority writes the identity.
     */
    static SubjectPublicKeyInfo verifiedPublicKey(byte[] csrDer) {
        PKCS10CertificationRequest csr;
        try {
            csr = new PKCS10CertificationRequest(csrDer);
        } catch (IOException | RuntimeException e) {
            throw new InvalidCertificateRequestException(UNREADABLE);
        }
        var publicKey = csr.getSubjectPublicKeyInfo();
        if (!CertificateAuthority.isP256(publicKey)) {
            throw new InvalidCertificateRequestException(P256_REQUIRED);
        }
        boolean valid;
        try {
            valid = csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publicKey));
        } catch (OperatorCreationException | PKCSException | RuntimeException e) {
            valid = false;
        }
        if (!valid) {
            throw new InvalidCertificateRequestException(BAD_SIGNATURE);
        }
        return publicKey;
    }

    /** Signs a 30-day certificate for the host and records it. Call inside a transaction. */
    EnrolledCertificate issueAndRecord(UUID hostId, SubjectPublicKeyInfo publicKey, Instant now) {
        var issued = authority.issueHostCertificate(hostId, publicKey, now);
        var certificate = issued.certificate();
        jdbc.sql("""
                        insert into hosts.host_certificates
                            (serial, host_id, fingerprint_sha256, not_before, not_after, created_at)
                        values (:serial, :hostId, :fingerprint, :notBefore, :notAfter, :now)
                        """)
                .param("serial", new BigDecimal(certificate.getSerialNumber()))
                .param("hostId", hostId)
                .param("fingerprint", issued.fingerprintSha256())
                .param("notBefore", Timestamp.from(certificate.getNotBefore().toInstant()))
                .param("notAfter", Timestamp.from(certificate.getNotAfter().toInstant()))
                .param("now", Timestamp.from(now))
                .update();
        try {
            return new EnrolledCertificate(
                    List.of(certificate.getEncoded(), authority.intermediate().getEncoded()),
                    certificate.getNotAfter().toInstant());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
