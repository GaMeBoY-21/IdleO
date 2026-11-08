package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestHosts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staged rollouts (ADR 0056): a release starts at 5% of hosts chosen stably by id, widening only adds hosts, pausing is
 * immediate, widening waits for another admin, and a release whose hosts reconnect or lose sessions far more than the
 * rest pauses itself. Each test has a release of its own.
 */
@ControlPlaneTest
class RolloutTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    AgentRollouts rollouts;

    @LocalGrpcServerPort
    int grpcPort;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    @Test
    void aReleaseStartsAtFivePercentChosenStablyByHostAndWideningOnlyAddsHosts() {
        var release = "9.9.1";
        rollouts.started(release);
        rollouts.started(release);
        assertThat(rollouts.find(release))
                .hasValueSatisfying(rollout -> assertThat(rollout.percent()).isEqualTo(5));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'rollout.start' and target_id = ?",
                        Integer.class,
                        release))
                .as("started once")
                .isEqualTo(1);

        var atFive = new HashSet<UUID>();
        var atTwentyFive = new HashSet<UUID>();
        int hosts = 10_000;
        for (int i = 0; i < hosts; i++) {
            var host = UUID.nameUUIDFromBytes(("host " + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int bucket = ReleaseManifest.bucket(host, release);
            assertThat(ReleaseManifest.bucket(host, release))
                    .as("the same host, the same bucket")
                    .isEqualTo(bucket);
            if (bucket < rollouts.offered(release, 100)) {
                atFive.add(host);
            }
            if (bucket < 25) {
                atTwentyFive.add(host);
            }
        }
        assertThat(atFive.size() / (double) hosts).isBetween(0.04, 0.06);
        assertThat(atTwentyFive).containsAll(atFive);
        assertThat(rollouts.offered(release, 3))
                .as("never more than the signed manifest allows")
                .isEqualTo(3);
    }

    @Test
    void pausingIsImmediateAndWideningWaitsForAnotherAdmin() throws Exception {
        var release = "9.9.2";
        rollouts.started(release);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var paused = send(
                admin.as(post("/admin/v1/rollouts/" + release + "/pause")),
                "{\"reason\":\"its hosts crash on start\"}");
        assertThat(paused.getStatus()).as(paused.getContentAsString()).isEqualTo(200);
        assertThat(rollouts.offered(release, 100)).isZero();

        var narrower = send(
                admin.as(post("/admin/v1/rollouts/" + release + "/widen")),
                "{\"percent\":10,\"reason\":\"a step that isn't one\"}");
        assertThat(narrower.getStatus()).isEqualTo(400);

        TestAdmins.signIn(mvc, jdbc, clock);
        var asked = send(
                admin.as(post("/admin/v1/rollouts/" + release + "/widen")),
                "{\"percent\":25,\"reason\":\"the fix is in this build\"}");
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        assertThat(JSON.readTree(asked.getContentAsString()).get("state").asString())
                .isEqualTo("pending");
        assertThat(rollouts.offered(release, 100))
                .as("still paused until approved")
                .isZero();

        TestAdmins.approve(mvc, jdbc, clock, asked.getContentAsString());
        assertThat(rollouts.offered(release, 100)).isEqualTo(25);
        assertThat(jdbc.queryForList(
                        "select action from identity.audit_log where target_type = 'rollout' and target_id = ?"
                                + " order by seq",
                        String.class,
                        release))
                .containsExactly("rollout.start", "rollout.pause", "rollout.widen");

        var list = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/rollouts")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var shown = false;
        for (var rollout : list.get("items")) {
            if (rollout.get("id").asString().equals(release)) {
                shown = true;
                assertThat(rollout.get("percent").asInt()).isEqualTo(25);
                assertThat(rollout.get("paused").asBoolean()).isFalse();
            }
        }
        assertThat(shown).isTrue();
    }

    @Test
    void aReleaseWhoseHostsReconnectFarMoreThanTheRestPausesItself() throws Exception {
        var release = "9.9.3";
        rollouts.started(release);
        var host = TestHosts.connect(mvc, grpcPort);
        jdbc.update("update hosts.hosts set agent_version = ? where id = ?", release, host.id());
        var now = Instant.now();
        for (int i = 0; i < 20; i++) {
            jdbc.update(
                    "insert into agentgateway.presence_changes (id, host_id, state, at) values (?, ?, 'online', ?)",
                    UUID.randomUUID(),
                    host.id(),
                    Timestamp.from(now.minus(Duration.ofMinutes(30 + i))));
        }
        // Pausing by itself tells the Owners: only this test's, not every earlier test's in the shared database.
        jdbc.update("update identity.admins set locked_at = now() where locked_at is null");
        try {
            rollouts.checkNow();
            assertThat(rollouts.find(release)).hasValueSatisfying(rollout -> {
                assertThat(rollout.paused()).isTrue();
                assertThat(rollout.reason()).startsWith("paused by itself: its hosts reconnected");
            });
            assertThat(jdbc.queryForObject(
                            "select actor_kind from identity.audit_log where action = 'rollout.pause' and target_id = ?",
                            String.class,
                            release))
                    .isEqualTo("system");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aReleaseWhoseHostsLoseFarMoreSessionsWouldPauseAndOneDoingAsWellWouldNot() {
        var spike = new AgentRollouts.Health(3, 10, 5, 0.1, 40, 200, 10, 0.1);
        assertThat(rollouts.worse(spike)).isEqualTo("its hosts lost 5 of 10 sessions (50%) against 5% on the rest");
        var fine = new AgentRollouts.Health(3, 10, 1, 0.1, 40, 200, 10, 0.1);
        assertThat(rollouts.worse(fine)).isNull();
        var tooFew = new AgentRollouts.Health(1, 2, 2, 0.1, 40, 200, 10, 0.1);
        assertThat(rollouts.worse(tooFew)).as("two sessions say nothing yet").isNull();
    }
}
