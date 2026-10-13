package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.identity.AuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Activation by operator command (ADR 0015): only a host that can really work, audited, re-runnable. */
@ControlPlaneTest
class HostActivationTests {

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("test-operator");

    @Autowired
    HostActivation activation;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private String status(TestAgent agent) {
        return jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, agent.hostId);
    }

    private int changes(TestAgent agent) {
        return jdbc.queryForObject(
                "select count(*) from hosts.status_changes where host_id = ?", Integer.class, agent.hostId);
    }

    @Test
    void aHostWithoutAListingOrAReportedGpuIsNotActivated() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);

        assertThatThrownBy(() -> activation.activate(agent.hostId, "Pilot host", OPERATOR))
                .isInstanceOf(HostActivation.Refused.class)
                .hasMessageContaining("no listing");
        TestHosts.list(mvc, agent, 22, 80);
        assertThatThrownBy(() -> activation.activate(agent.hostId, "Pilot host", OPERATOR))
                .isInstanceOf(HostActivation.Refused.class)
                .hasMessageContaining("no reported GPU");
        assertThat(status(agent)).isEqualTo("pending");
        assertThat(changes(agent)).isZero();
    }

    @Test
    void aHostWithoutAValidCertificateIsNotActivated() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 22, 80);
            jdbc.update("update hosts.host_certificates set revoked_at = now() where host_id = ?", host.id());

            assertThatThrownBy(() -> activation.activate(host.id(), "Pilot host", OPERATOR))
                    .isInstanceOf(HostActivation.Refused.class)
                    .hasMessageContaining("no valid certificate");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aReadyHostIsActivatedOnceAndEveryChangeIsAudited() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 22, 80);

            var first = activation.activate(host.id(), "Pilot host", OPERATOR);
            var again = activation.activate(host.id(), "Pilot host", OPERATOR);
            var suspended = activation.suspend(host.id(), "Overheating reports", OPERATOR);

            assertThat(first).isEqualTo(new HostActivation.Change(host.id(), "pending", "active", true));
            assertThat(again.changed()).isFalse();
            assertThat(suspended).isEqualTo(new HostActivation.Change(host.id(), "active", "suspended", true));
            assertThat(status(host.agent())).isEqualTo("suspended");
            assertThat(jdbc.queryForList(
                            "select from_status || '>' || to_status || ':' || changed_by from hosts.status_changes"
                                    + " where host_id = ? order by created_at",
                            String.class,
                            host.id()))
                    .containsExactly("pending>active:test-operator", "active>suspended:test-operator");
            assertThatThrownBy(() -> jdbc.update("delete from hosts.status_changes where host_id = ?", host.id()))
                    .hasMessageContaining("append-only");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void anUnknownHostOrAMissingReasonIsRefused() {
        assertThatThrownBy(() -> activation.activate(java.util.UUID.randomUUID(), "Pilot host", OPERATOR))
                .isInstanceOf(HostActivation.Refused.class)
                .hasMessageContaining("no host");
        assertThatThrownBy(() -> activation.activate(java.util.UUID.randomUUID(), " ", OPERATOR))
                .isInstanceOf(HostActivation.Refused.class);
    }
}
