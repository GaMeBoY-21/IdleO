package com.idleo.controlplane.hosts.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness's {@code ca} component and the CA's expiry gauges (ADR 0010). The intermediate and the root were checked
 * against each other at startup ({@link CertificateAuthority}); what can change while the instance runs is the time, so
 * this says DOWN once either is outside its validity, when no host could enrol or renew, and the gauge
 * {@code hosts.ca.certificate.expiry} gives the seconds each has left, which the certificates alert reads
 * (ops/alerts, docs/runbooks/alerts.md). The agent port's own server certificate is Spring's SSL bundle, whose expiry
 * Spring Boot measures itself ({@code ssl.chain.expiry}).
 */
@Component("caHealthIndicator")
class CertificateAuthorityHealth implements HealthIndicator {

    private final CertificateAuthority authority;
    private final Clock clock;

    CertificateAuthorityHealth(CertificateAuthority authority, Clock clock, MeterRegistry registry) {
        this.authority = authority;
        this.clock = clock;
        expiryGauge(registry, "root", authority.root());
        expiryGauge(registry, "intermediate", authority.intermediate());
    }

    @Override
    public Health health() {
        var now = Instant.now(clock);
        if (!valid(authority.root(), now) || !valid(authority.intermediate(), now)) {
            return Health.down()
                    .withDetail("error", "a CA certificate is outside its validity")
                    .build();
        }
        return Health.up().build();
    }

    private void expiryGauge(MeterRegistry registry, String which, X509CertificateHolder certificate) {
        Gauge.builder(
                        "hosts.ca.certificate.expiry",
                        () -> Duration.between(
                                        Instant.now(clock),
                                        certificate.getNotAfter().toInstant())
                                .toSeconds())
                .tag("certificate", which)
                .description("Seconds until this CA certificate expires; negative once it has")
                .baseUnit("seconds")
                .register(registry);
    }

    private static boolean valid(X509CertificateHolder certificate, Instant now) {
        return !now.isBefore(certificate.getNotBefore().toInstant())
                && now.isBefore(certificate.getNotAfter().toInstant());
    }
}
