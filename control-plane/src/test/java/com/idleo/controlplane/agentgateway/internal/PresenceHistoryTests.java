package com.idleo.controlplane.agentgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestHosts;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each host's presence over time (ADR 0056): a stream opening is online, closing is offline, a record that lapsed with no
 * close is offline at the sweep, a reconnect counts even when the new stream replaced the old first, nothing records an
 * address, and an old observation never writes over a newer state.
 */
@ControlPlaneTest
class PresenceHistoryTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PresenceHistory history;

    @Autowired
    PresenceHistorySweep sweep;

    @LocalGrpcServerPort
    int grpcPort;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private List<String> states(UUID host) {
        return jdbc.queryForList(
                "select state from agentgateway.presence_changes where host_id = ? order by at, id",
                String.class,
                host);
    }

    @Test
    void openingAndClosingAStreamAreItsChangesAndAReconnectCountsOnItsOwn() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        Awaitility.await().atMost(WAIT).until(() -> states(host.id()).equals(List.of("online")));

        // The agent connects again before its old stream is closed: the new one replaces it, and still counts.
        var again = TestHosts.reconnect(host, grpcPort, "0.1.0");
        Awaitility.await()
                .atMost(WAIT)
                .until(() -> states(host.id()).stream().filter("online"::equals).count() == 2);

        again.disconnect();
        Awaitility.await().atMost(WAIT).until(() -> states(host.id()).getLast().equals("offline"));
        assertThat(jdbc.queryForList(
                        "select column_name from information_schema.columns where table_schema = 'agentgateway'"
                                + " and table_name = 'presence_changes'",
                        String.class))
                .as("only the change and when: no address")
                .containsExactlyInAnyOrder("id", "host_id", "state", "at");
    }

    @Test
    void aRecordThatLapsedWithNoCloseIsOfflineAtTheSweep() throws Exception {
        var host = UUID.randomUUID();
        history.opened(host, clock.instant().minusSeconds(60));
        sweep.sweepNow();
        assertThat(states(host)).containsExactly("online", "offline");
        sweep.sweepNow();
        assertThat(states(host)).as("only changes are kept").containsExactly("online", "offline");
    }

    @Test
    void anOldObservationNeverWritesOverANewerState() {
        var host = UUID.randomUUID();
        var now = clock.instant();
        history.opened(host, now);
        history.offline(host, now.minusSeconds(5));
        history.unknown(host, now.minusSeconds(5));
        assertThat(states(host)).containsExactly("online");
    }

    @Test
    void theHostPageShowsTheChangesAndTheReconnectsADay() throws Exception {
        var host = UUID.randomUUID();
        var now = clock.instant();
        for (int i = 3; i >= 1; i--) {
            history.opened(host, now.minus(Duration.ofHours(i)));
            history.offline(host, now.minus(Duration.ofHours(i)).plusSeconds(600));
        }
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var page = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/hosts/" + host + "/presence")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(page.get("changes")).hasSize(6);
        assertThat(page.get("changes").get(0).get("state").asString()).isEqualTo("offline");
        long opened = 0;
        for (var day : page.get("days")) {
            opened += day.get("opened").asLong();
        }
        assertThat(opened).isEqualTo(3);
    }

    @Test
    void changesAreKeptNinetyDays() {
        var host = UUID.randomUUID();
        jdbc.update(
                "insert into agentgateway.presence_changes (id, host_id, state, at) values (?, ?, 'online', ?)",
                UUID.randomUUID(),
                host,
                Timestamp.from(Instant.now().minus(Duration.ofDays(91))));
        history.opened(host, clock.instant());
        new PresenceRetention()
                .presenceChangesRule(
                        org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
                        context().getBean(com.idleo.controlplane.RetentionPeriods.class))
                .apply(clock.instant());
        assertThat(states(host)).containsExactly("online");
    }

    @Autowired
    org.springframework.context.ApplicationContext context;

    private org.springframework.context.ApplicationContext context() {
        return context;
    }
}
