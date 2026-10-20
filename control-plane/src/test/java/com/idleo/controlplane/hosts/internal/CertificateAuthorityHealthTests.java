package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;

/** Readiness's {@code ca} component and the expiry gauges the certificates alert reads. */
@ControlPlaneTest
class CertificateAuthorityHealthTests {

    @Autowired
    CertificateAuthorityHealth health;

    @Autowired
    CertificateAuthority authority;

    @Autowired
    MutableClock clock;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void upWhileBothCertificatesAreValid() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downOnceTheIntermediateHasExpired() {
        var left = Duration.between(
                clock.instant(), authority.intermediate().getNotAfter().toInstant());
        clock.advance(left.plusMinutes(1));

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void theGaugesGiveTheSecondsEachCertificateHasLeft() {
        var intermediate = registry.get("hosts.ca.certificate.expiry")
                .tag("certificate", "intermediate")
                .gauge()
                .value();
        var expected = Duration.between(
                        clock.instant(), authority.intermediate().getNotAfter().toInstant())
                .toSeconds();
        assertThat(intermediate).isBetween(expected - 5.0, expected + 5.0);
        var root = registry.get("hosts.ca.certificate.expiry")
                .tag("certificate", "root")
                .gauge()
                .value();
        assertThat(root).isGreaterThan(intermediate);

        clock.advance(Duration.ofDays(30));
        assertThat(registry.get("hosts.ca.certificate.expiry")
                        .tag("certificate", "intermediate")
                        .gauge()
                        .value())
                .isBetween(
                        intermediate - Duration.ofDays(30).toSeconds() - 5.0,
                        intermediate - Duration.ofDays(30).toSeconds() + 5.0);
    }
}
