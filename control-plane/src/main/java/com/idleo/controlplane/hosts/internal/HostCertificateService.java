package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.EnrolledCertificate;
import com.idleo.controlplane.hosts.HostCertificates;
import com.idleo.controlplane.hosts.RenewalNotOpenException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent authentication by certificate fingerprint, and renewal in a certificate's last 10 days
 * (technical design line 752: renewed after 20 of 30 days). The renewal window stops a broken or
 * hostile agent from minting certificates in a loop.
 */
@Service
class HostCertificateService implements HostCertificates {

    static final Duration RENEWAL_WINDOW = Duration.ofDays(10);

    private final CertificateIssuer issuer;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    HostCertificateService(CertificateIssuer issuer, JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.issuer = issuer;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    private record CertificateRow(UUID hostId, Instant notBefore, Instant notAfter, boolean revoked, String status) {}

    @Override
    public CertificateCheck authenticate(byte[] fingerprintSha256) {
        var now = Instant.now(clock);
        var row = find(fingerprintSha256);
        if (row.isEmpty()
                || row.get().revoked()
                || now.isBefore(row.get().notBefore())
                || !now.isBefore(row.get().notAfter())) {
            return new CertificateCheck.Invalid();
        }
        if (HostStatus.SUSPENDED.dbValue().equals(row.get().status())) {
            return new CertificateCheck.HostSuspended(row.get().hostId());
        }
        return new CertificateCheck.Valid(row.get().hostId());
    }

    @Override
    public EnrolledCertificate renew(UUID hostId, byte[] currentFingerprintSha256, byte[] csrDer) {
        var publicKey = CertificateIssuer.verifiedPublicKey(csrDer);
        var current = find(currentFingerprintSha256)
                .filter(row -> row.hostId().equals(hostId) && !row.revoked())
                .orElseThrow(() -> new IllegalStateException("renewal from a certificate that didn't authenticate"));
        var now = Instant.now(clock);
        var opensAt = current.notAfter().minus(RENEWAL_WINDOW);
        if (now.isBefore(opensAt)) {
            throw new RenewalNotOpenException(opensAt);
        }
        return transactions.execute(status -> issuer.issueAndRecord(hostId, publicKey, now));
    }

    private Optional<CertificateRow> find(byte[] fingerprintSha256) {
        return jdbc.sql("""
                        select c.host_id, c.not_before, c.not_after, c.revoked_at is not null as revoked, h.status
                        from hosts.host_certificates c
                        join hosts.hosts h on h.id = c.host_id
                        where c.fingerprint_sha256 = :fingerprint
                        """)
                .param("fingerprint", fingerprintSha256)
                .query((rs, n) -> new CertificateRow(
                        rs.getObject("host_id", UUID.class),
                        rs.getTimestamp("not_before").toInstant(),
                        rs.getTimestamp("not_after").toInstant(),
                        rs.getBoolean("revoked"),
                        rs.getString("status")))
                .optional();
    }
}
