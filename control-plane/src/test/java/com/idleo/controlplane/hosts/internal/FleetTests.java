package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestHosts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fleet (ADR 0056): hosts by agent version, distribution, GPU, driver and blocklist, each a filter of the one list
 * engine; reconnects a day from the presence history; and the whole fleet counted the same ways.
 */
@ControlPlaneTest
class FleetTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private JsonNode read(TestAdmins.TestAdmin admin, String path) throws Exception {
        var response = mvc.perform(admin.as(get(path))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString());
    }

    @Test
    void aHostShowsItsVersionsAndReconnectsAndEachIsAFilter() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            var version = "8.8." + (System.nanoTime() % 1000);
            jdbc.update(
                    "update hosts.hosts set agent_version = ?, blocklist_version = '2026-11-01T00:00:00Z' where id = ?",
                    version,
                    host.id());
            var now = Instant.now();
            // With the stream it opened just now: 15 in the week.
            for (int i = 0; i < 14; i++) {
                jdbc.update(
                        "insert into agentgateway.presence_changes (id, host_id, state, at) values (?, ?, 'online', ?)",
                        UUID.randomUUID(),
                        host.id(),
                        Timestamp.from(now.minus(Duration.ofHours(1 + i))));
            }
            var admin = TestAdmins.signIn(mvc, jdbc, clock);
            var page = read(admin, "/admin/v1/fleet?agentVersion=" + version);
            assertThat(page.get("hosts")).hasSize(1);
            var shown = page.get("hosts").get(0);
            assertThat(shown.get("id").asString()).isEqualTo(host.id().toString());
            assertThat(shown.get("blocklistVersion").asString()).isEqualTo("2026-11-01T00:00:00Z");
            assertThat(shown.get("reconnectsPerDay").asDouble())
                    .as("15 streams in a week: 14 reconnects")
                    .isEqualTo(2.0);
            assertThat(read(admin, "/admin/v1/fleet?agentVersion=" + version + "&offline=day")
                            .get("hosts"))
                    .as("seen just now")
                    .isEmpty();

            var summary = read(admin, "/admin/v1/fleet/summary");
            var counted = false;
            for (var count : summary.get("agentVersions")) {
                if (count.get("value").asString().equals(version)) {
                    counted = count.get("hosts").asLong() == 1;
                }
            }
            assertThat(counted).as("its version counted once").isTrue();
            assertThat(summary.get("hosts").asLong())
                    .isEqualTo(jdbc.queryForObject(
                            "select count(*) from hosts.hosts where status <> 'removed'", Long.class));
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aHostNotSeenForADayIsOffline() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        host.disconnect();
        var version = "8.9." + (System.nanoTime() % 1000);
        jdbc.update(
                "update hosts.hosts set agent_version = ?, last_seen_at = ? where id = ?",
                version,
                Timestamp.from(Instant.now().minus(Duration.ofHours(30))),
                host.id());
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        assertThat(read(admin, "/admin/v1/fleet?agentVersion=" + version + "&offline=day")
                        .get("hosts"))
                .hasSize(1);
    }
}
