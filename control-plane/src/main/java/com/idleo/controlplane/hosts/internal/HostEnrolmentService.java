package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.EnrolledCertificate;
import com.idleo.controlplane.hosts.HostEnrolment;
import com.idleo.controlplane.hosts.InvalidEnrolmentCodeException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

    private final CertificateIssuer issuer;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    HostEnrolmentService(CertificateIssuer issuer, JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.issuer = issuer;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public EnrolledCertificate enrol(String enrolmentCode, byte[] csrDer) {
        var publicKey = CertificateIssuer.verifiedPublicKey(csrDer);
        var codeHash = EnrolmentCodes.hash(
                EnrolmentCodes.normalise(enrolmentCode).orElseThrow(InvalidEnrolmentCodeException::new));

        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var hostId = useCode(codeHash, now).orElseThrow(InvalidEnrolmentCodeException::new);
            return issuer.issueAndRecord(hostId, publicKey, now, CertificateIssuer.Occasion.ENROLMENT);
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
}
