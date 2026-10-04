package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.EnrolledCertificate;
import com.idleo.controlplane.hosts.HostEnrolment;
import com.idleo.controlplane.hosts.InvalidCertificateRequestException;
import com.idleo.controlplane.hosts.InvalidEnrolmentCodeException;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enrolment: the code is used up, the certificate is issued and its fingerprint recorded, all in one
 * transaction, so a crash can never leave a used code without a recorded certificate or the other
 * way round. Two calls racing on one code give exactly one certificate: the update that marks the
 * code used matches at most once.
 *
 * <p>Codes, CSRs and keys are never logged.
 */
@Service
class HostEnrolmentService implements HostEnrolment {

    static final String P256_REQUIRED = "The certificate signing request must be for an EC P-256 key.";
    static final String UNREADABLE = "The certificate signing request is not a valid DER-encoded PKCS#10 request.";
    static final String BAD_SIGNATURE = "The certificate signing request's signature does not verify.";

    private final CertificateAuthority authority;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    HostEnrolmentService(
            CertificateAuthority authority, JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.authority = authority;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public EnrolledCertificate enrol(String enrolmentCode, byte[] csrDer) {
        var publicKey = verifiedPublicKey(csrDer);
        var codeHash = EnrolmentCodes.hash(
                EnrolmentCodes.normalise(enrolmentCode).orElseThrow(InvalidEnrolmentCodeException::new));

        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var hostId = useCode(codeHash, now).orElseThrow(InvalidEnrolmentCodeException::new);
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
                    .param(
                            "notBefore",
                            Timestamp.from(certificate.getNotBefore().toInstant()))
                    .param("notAfter", Timestamp.from(certificate.getNotAfter().toInstant()))
                    .param("now", Timestamp.from(now))
                    .update();
            try {
                return new EnrolledCertificate(
                        List.of(
                                certificate.getEncoded(),
                                authority.intermediate().getEncoded()),
                        certificate.getNotAfter().toInstant());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Marks the code used and returns its host, if the code is live: known, unused and unexpired. */
    private Optional<UUID> useCode(byte[] codeHash, Instant now) {
        return jdbc.sql("""
                        update hosts.enrolment_codes set used_at = :now
                        where code_hash = :hash and used_at is null and expires_at > :now
                        returning host_id
                        """)
                .param("now", Timestamp.from(now))
                .param("hash", codeHash)
                .query(UUID.class)
                .optional();
    }

    /**
     * Only the CSR's public key is used. Its subject, names and requested extensions are ignored on
     * purpose: the certificate authority writes the identity itself.
     */
    private static SubjectPublicKeyInfo verifiedPublicKey(byte[] csrDer) {
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
        try {
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publicKey))) {
                throw new InvalidCertificateRequestException(BAD_SIGNATURE);
            }
        } catch (OperatorCreationException | PKCSException | RuntimeException e) {
            if (e instanceof InvalidCertificateRequestException refused) {
                throw refused;
            }
            throw new InvalidCertificateRequestException(BAD_SIGNATURE);
        }
        return publicKey;
    }
}
