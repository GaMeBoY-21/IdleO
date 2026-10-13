package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.identity.AuditTrail;
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The hosts module's admin actions (ADR 0027): hosts read in full, activated, suspended, cut off; all audited. */
@ControlPlaneTest
class AdminHostsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    HostActivation activation;

    @Autowired
    TransactionTemplate transactions;

    @LocalGrpcServerPort
    int grpcPort;

    private final JsonMapper json = JsonMapper.builder().build();

    private static MockHttpServletRequestBuilder reason(MockHttpServletRequestBuilder request, String reason) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestAdmins.TestAdmin admin) throws Exception {
        return json.readTree(mvc.perform(admin.as(request))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private int audited(String action, UUID hostId) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_type = 'host' and target_id = ?",
                Integer.class,
                action,
                hostId.toString());
    }

    @Test
    void readsAHostWithItsGpusCertificatesHistoryAndTelemetryAndFindsIt() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 22, 80);
            Thread.sleep(700);
            var view = read(get("/admin/v1/hosts/" + host.id()), admin);
            assertThat(view.get("status").asString()).isEqualTo("pending");
            assertThat(view.get("online").asBoolean()).isTrue();
            assertThat(view.get("pricePaisePerMin").asInt()).isEqualTo(22);
            assertThat(view.get("gpus").get(0).get("uuid").asString()).isEqualTo(TestAgent.GPU_A);
            assertThat(view.get("certificates")).hasSize(1);
            assertThat(view.get("certificates").get(0).get("revokedAt").isNull())
                    .isTrue();
            assertThat(view.get("telemetry").get("now").isNull()).isFalse();
            // Its certificate's issue is in the audit log, by the system.
            assertThat(jdbc.queryForObject(
                            "select actor_kind || ':' || actor_name from identity.audit_log"
                                    + " where action = 'certificate.issue' and target_id = ?",
                            String.class,
                            host.id().toString()))
                    .isEqualTo("system:agent enrolment");

            var found = read(get("/admin/v1/hosts").param("query", host.id().toString()), admin);
            assertThat(found.get("hosts")).hasSize(1);
            assertThat(found.get("hosts").get(0).get("online").asBoolean()).isTrue();
            assertThat(found.get("hosts").get(0).get("gpuModels").get(0).asString())
                    .isNotBlank();
            mvc.perform(admin.as(get("/admin/v1/hosts/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        } finally {
            host.disconnect();
        }
    }

    @Test
    void activatesAndSuspendsThroughTheCommandsServiceEachAuditedOnce() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            mvc.perform(admin.as(reason(post("/admin/v1/hosts/" + host.id() + "/activate"), "passed the canary job")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("/problems/action-refused"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no listing")));
            assertThat(audited("host.activate", host.id())).isZero();

            TestHosts.list(mvc, host.agent(), 22, 80);
            var active =
                    read(reason(post("/admin/v1/hosts/" + host.id() + "/activate"), "passed the canary job"), admin);
            assertThat(active.get("status").asString()).isEqualTo("active");
            read(reason(post("/admin/v1/hosts/" + host.id() + "/activate"), "passed the canary job"), admin);
            assertThat(audited("host.activate", host.id())).isOne();
            var history = active.get("statusHistory").get(0);
            assertThat(history.get("to").asString()).isEqualTo("active");
            assertThat(history.get("changedBy").asString()).startsWith("admin renter-");

            var suspended =
                    read(reason(post("/admin/v1/hosts/" + host.id() + "/suspend"), "overheating reports"), admin);
            assertThat(suspended.get("status").asString()).isEqualTo("suspended");
            assertThat(audited("host.suspend", host.id())).isOne();
            assertThat(jdbc.queryForObject(
                            "select actor_user_id from identity.audit_log where action = 'host.suspend' and target_id = ?",
                            UUID.class,
                            host.id().toString()))
                    .isEqualTo(admin.id());
        } finally {
            host.disconnect();
        }
    }

    @Test
    void anActivationAndItsAuditRowRollBackTogether() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 22, 80);
            transactions.executeWithoutResult(status -> {
                activation.activate(host.id(), "rolled back", AuditTrail.Actor.operator("tester"));
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, host.id()))
                    .isEqualTo("pending");
            assertThat(audited("host.activate", host.id())).isZero();
            assertThat(jdbc.queryForObject(
                            "select count(*) from hosts.status_changes where host_id = ?", Integer.class, host.id()))
                    .isZero();
        } finally {
            host.disconnect();
        }
    }

    @Test
    void activateHostAndSuspendHostStillWorkThroughTheSameService() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 22, 80);
            var out = new ByteArrayOutputStream();
            new HostCommands()
                    .activateHost(activation)
                    .run(
                            OperatorCommand.Arguments.parse(
                                    "activate-host", "--host", host.id().toString(), "--reason", "pilot host"),
                            "nikki",
                            new PrintStream(out, true, StandardCharsets.UTF_8));
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("pending -> active, by nikki");
            var suspended = new ByteArrayOutputStream();
            new HostCommands()
                    .suspendHost(activation)
                    .run(
                            OperatorCommand.Arguments.parse(
                                    "suspend-host", "--host", host.id().toString(), "--reason", "overheating"),
                            "nikki",
                            new PrintStream(suspended, true, StandardCharsets.UTF_8));
            assertThat(suspended.toString(StandardCharsets.UTF_8)).contains("active -> suspended, by nikki");
            assertThat(audited("host.suspend", host.id())).isOne();
            assertThat(jdbc.queryForObject(
                            "select actor_kind || ':' || actor_name from identity.audit_log"
                                    + " where action = 'host.activate' and target_id = ?",
                            String.class,
                            host.id().toString()))
                    .isEqualTo("operator:nikki");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void revokingCertificatesCutsTheAgentOffAndIsAuditedOnce() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            var revoked = read(
                    reason(post("/admin/v1/hosts/" + host.id() + "/revoke-certificates"), "its key was leaked"), admin);
            assertThat(revoked.get("certificates").get(0).get("revokedAt").isNull())
                    .isFalse();
            var ended = host.stream().endStatus(Duration.ofSeconds(5));
            assertThat(ended).isNotNull();
            assertThat(ended.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
            read(reason(post("/admin/v1/hosts/" + host.id() + "/revoke-certificates"), "its key was leaked"), admin);
            assertThat(audited("certificate.revoke", host.id())).isOne();
            assertThat(jdbc.queryForObject(
                            "select jsonb_array_length(before->'serials') from identity.audit_log"
                                    + " where action = 'certificate.revoke' and target_id = ?",
                            Integer.class,
                            host.id().toString()))
                    .isOne();
        } finally {
            host.disconnect();
        }
    }

    @Test
    void anOwnerRemovingTheirHostRevokesItsCertificatesInTheAuditLogToo() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        host.disconnect();
        mvc.perform(delete("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForObject(
                        "select actor_kind || ':' || reason from identity.audit_log"
                                + " where action = 'certificate.revoke' and target_id = ?",
                        String.class,
                        host.id().toString()))
                .isEqualTo("owner:removed by its owner");
    }
}
