package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.hosts.HostAgentReports;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.test.web.servlet.MockMvc;

/** The hosts module's meters (ADR 0036): hosts by state and connection, self-tests and update results. */
@ControlPlaneTest
class HostMetricsTests {

    @Autowired
    HostMetrics metrics;

    @Autowired
    HostAgentReports reports;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MockMvc mvc;

    @LocalGrpcServerPort
    int grpcPort;

    private double gauge(String name, String... tags) {
        var gauge = meters.find(name).tags(tags).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aConnectedPendingHostIsCountedOnlineAndItsReportsAreCounted() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            metrics.refresh();
            var online = gauge("hosts", "state", "pending", "online", "true");
            assertThat(online).isGreaterThanOrEqualTo(1);
            host.disconnect();
            // Its presence goes once its stream has closed.
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            do {
                Thread.sleep(200);
                metrics.refresh();
            } while (gauge("hosts", "state", "pending", "online", "true") >= online && System.nanoTime() < deadline);
            assertThat(gauge("hosts", "state", "pending", "online", "true")).isLessThan(online);
            assertThat(gauge("hosts", "state", "pending", "online", "false")).isGreaterThanOrEqualTo(1);

            var failedBefore = count("agents.self-test.failures", "check", "gpu_in_distro");
            reports.recordSelfTest(
                    host.id(),
                    new HostAgentReports.SelfTest(
                            false,
                            "0.1.0",
                            List.of(
                                    new HostAgentReports.SelfTestCheck("disk", true, "120 GB free", 5),
                                    new HostAgentReports.SelfTestCheck("gpu_in_distro", false, "no GPU", 9))));
            assertThat(count("agents.self-test.failures", "check", "gpu_in_distro") - failedBefore)
                    .isEqualTo(1);

            reports.recordUpdateResult(host.id(), "9.8.7", HostAgentReports.UpdateOutcome.ROLLED_BACK, "trial failed");
            assertThat(count("agents.updates", "version", "9.8.7", "outcome", "rolled_back"))
                    .isEqualTo(1);
            metrics.refresh();
            assertThat(gauge("agents.update.results", "version", "9.8.7", "outcome", "rolled_back"))
                    .isEqualTo(1);
        } finally {
            host.disconnect();
        }
    }
}
