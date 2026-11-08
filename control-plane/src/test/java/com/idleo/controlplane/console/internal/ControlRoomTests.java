package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live dashboard (ADR 0056): each panel's rollup is the same as the raw data says, money ties to the money reports
 * (and so to the ledger), money is only for an admin who can read it, and the rollup is worked out by the job, never on
 * a page view. The seeded data's budgets are {@code ConsoleBudgetTests}'.
 */
@ControlPlaneTest
class ControlRoomTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ControlRoomRollups rollups;

    private TestAdmins.TestAdmin owner;

    @BeforeEach
    void anOwnerAndFreshRollups() throws Exception {
        owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        rollups.rollUp(true);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode dashboard(TestAdmins.TestAdmin admin) throws Exception {
        var response =
                mvc.perform(admin.as(get("/admin/v1/control-room"))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /**
     * The dashboard, rolled up afresh, with the raw figures as they were around it: another test's work going on in the
     * background can change a count between the two, so it's asked again until they held still.
     */
    private <T> Map.Entry<JsonNode, T> settled(java.util.function.Supplier<T> raw) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            var before = raw.get();
            rollups.rollUp(true);
            var shown = dashboard(owner);
            if (before.equals(raw.get())) {
                return Map.entry(shown, before);
            }
        }
        throw new AssertionError("the data never held still long enough to compare");
    }

    @Test
    void moneyIsTheMoneyReportsFiguresOverTheSameDays() throws Exception {
        var money = dashboard(owner).get("money");
        var today = LocalDate.ofInstant(clock.instant(), INDIA);
        var report = JSON.readTree(mvc.perform(owner.as(get("/admin/v1/money/report")
                        .param("interval", "day")
                        .param("from", today.minusDays(29).toString())
                        .param("to", today.toString())))
                .andReturn()
                .getResponse()
                .getContentAsString());
        Map<String, long[]> sums = new HashMap<>();
        for (var figure : List.of("gmv", "take", "refunds", "chargebacks")) {
            sums.put(figure, new long[3]);
        }
        for (var row : report.get("rows")) {
            var day = LocalDate.parse(row.get("start").asString());
            for (var figure : List.of("gmv", "take", "refunds", "chargebacks")) {
                var value = row.get("figures").get(figure).asLong();
                var windows = sums.computeIfAbsent(figure, key -> new long[3]);
                if (day.equals(today)) {
                    windows[0] += value;
                }
                if (!day.isBefore(today.minusDays(6))) {
                    windows[1] += value;
                }
                windows[2] += value;
            }
        }
        var windows = List.of("today", "week", "month");
        for (int i = 0; i < 3; i++) {
            var window = money.get(windows.get(i));
            assertThat(window.get("gmvPaise").asLong())
                    .as("gmv " + windows.get(i))
                    .isEqualTo(sums.get("gmv")[i]);
            assertThat(window.get("takePaise").asLong())
                    .as("take " + windows.get(i))
                    .isEqualTo(sums.get("take")[i]);
            assertThat(window.get("refundsPaise").asLong()).isEqualTo(sums.get("refunds")[i]);
            assertThat(window.get("chargebacksPaise").asLong()).isEqualTo(sums.get("chargebacks")[i]);
        }
    }

    @Test
    void anAdminWhoCantReadMoneySeesTheRestButNotTheMoney() throws Exception {
        var operations = TestAdmins.signInAs(mvc, jdbc, clock, "operations");
        var seen = dashboard(operations);
        assertThat(seen.get("money").isNull()).as("decided on the server").isTrue();
        assertThat(seen.get("computedAt").has("money")).isFalse();
        assertThat(seen.get("marketplace").isNull()).isFalse();
    }

    @Test
    void theMarketplaceIsWhatTheSessionsSay() throws Exception {
        var compared = settled(() -> List.of(
                count("select count(*) from sessions.sessions where state in ('RUNNING', 'FINISHING')"),
                count("select count(*) from sessions.sessions where state = 'REQUESTED'"),
                count("select count(*) from sessions.attempts where created_at >= now() - interval '24 hours'"),
                count("select count(*) from sessions.attempts where created_at >= now() - interval '24 hours'"
                        + " and running_at is not null"),
                (long) jdbc.queryForList(
                                "select reason from sessions.session_events where at >= now() - interval '24 hours'"
                                        + " and to_state in ('FAILED', 'NO_HOST_FREE', 'HOST_LOST')"
                                        + " group by reason order by count(*) desc, reason limit 5",
                                String.class)
                        .hashCode()));
        var marketplace = compared.getKey().get("marketplace");
        var raw = compared.getValue();
        assertThat(marketplace.get("sessionsRunning").asLong()).isEqualTo(raw.get(0));
        assertThat(marketplace.get("bookingsWaiting").asLong()).isEqualTo(raw.get(1));
        assertThat(marketplace.get("startsAttempted").asLong()).isEqualTo(raw.get(2));
        assertThat(marketplace.get("startsSucceeded").asLong()).isEqualTo(raw.get(3));
        var shown = new ArrayList<String>();
        marketplace
                .get("failures")
                .forEach(reason -> shown.add(reason.get("reason").asString()));
        assertThat((long) shown.hashCode())
                .as("the five commonest failures, in order")
                .isEqualTo(raw.get(4));
    }

    @Test
    void theFunnelsAreEachStepsPeopleFromItsModule() throws Exception {
        var compared = settled(() -> Map.of(
                "renters/1", count("select count(*) from identity.users where status <> 'erased'"),
                "renters/2", count("select count(distinct user_id) from billing.topups where status = 'paid'"),
                "renters/3",
                        count("select count(distinct renter_id) from sessions.sessions where started_at is not null"),
                "renters/4",
                        count("select count(*) from (select renter_id from sessions.sessions"
                                + " where started_at is not null group by renter_id having count(*) >= 2) two"),
                "hosts/1", count("select count(distinct owner_user_id) from hosts.hosts"),
                "hosts/4", count("select count(distinct user_id) from payouts.payouts where status = 'processed'")));
        var steps = new HashMap<String, Long>();
        compared.getKey()
                .get("funnels")
                .forEach(step -> steps.put(
                        step.get("funnel").asString() + "/" + step.get("order").asInt(),
                        step.get("people").asLong()));
        compared.getValue()
                .forEach((step, people) -> assertThat(steps.get(step)).as(step).isEqualTo(people));
        assertThat(steps.keySet()).hasSize(8);
    }

    @Test
    void healthHasTheControlPlanesOwnChecks() throws Exception {
        var names = new ArrayList<String>();
        dashboard(owner)
                .get("health")
                .forEach(check -> names.add(check.get("name").asString()));
        assertThat(names)
                .contains(
                        "database",
                        "valkey",
                        "backup age",
                        "agent streams",
                        "scanner",
                        "settlement reconciliation",
                        "inbox targets");
    }

    @Test
    void utilisationIsBusyOverOnlineHoursByModel() {
        var marketplace = new com.idleo.controlplane.ControlRoom.Marketplace(
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                List.of(),
                List.of(new com.idleo.controlplane.ControlRoom.ByModel("RTX 4080", 6)),
                List.of());
        var supply = new com.idleo.controlplane.ControlRoom.Supply(
                List.of(),
                List.of(
                        new com.idleo.controlplane.ControlRoom.ByModel("RTX 4080", 24),
                        new com.idleo.controlplane.ControlRoom.ByModel("RTX 3060", 12)),
                List.of());
        assertThat(ControlRoomView.utilisation(marketplace, supply))
                .containsExactly(
                        new com.idleo.controlplane.ControlRoom.ByModel("RTX 4080", 0.25),
                        new com.idleo.controlplane.ControlRoom.ByModel("RTX 3060", 0));
    }

    @Test
    void aPageViewReadsTheRollupsAndNeverWorksThemOut() throws Exception {
        var before = jdbc.queryForObject("select max(computed_at) from console.rollups", java.sql.Timestamp.class);
        dashboard(owner);
        dashboard(owner);
        assertThat(jdbc.queryForObject("select max(computed_at) from console.rollups", java.sql.Timestamp.class))
                .isEqualTo(before);
    }

    @Autowired
    ControlRoomStreams streams;

    @Test
    void theStreamSendsTheDashboardAtOnceAndAgainTenSecondsLater() throws Exception {
        var sent = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var recorder = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L) {
            @Override
            public void send(SseEventBuilder event) {
                sent.add(System.nanoTime());
            }
        };
        var started = System.nanoTime();
        streams.open(owner.id(), recorder);
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).until(() -> sent.size() >= 2);
        } finally {
            recorder.complete();
        }
        assertThat(Duration.ofNanos(sent.get(0) - started)).isLessThan(Duration.ofSeconds(2));
        var gap = Duration.ofNanos(sent.get(1) - sent.get(0));
        System.out.println("control room: stream updates " + gap.toMillis() + " ms apart");
        assertThat(gap).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(11));
    }

    @Test
    void theStreamEndpointOpensAnEventStreamForWhoCanReadTheDashboard() throws Exception {
        var opened = mvc.perform(owner.as(get("/admin/v1/control-room/stream"))).andReturn();
        assertThat(opened.getRequest().isAsyncStarted()).isTrue();
        assertThat(opened.getResponse().getContentType()).startsWith("text/event-stream");
        var nobody = TestAdmins.signInAs(mvc, jdbc, clock);
        assertThat(mvc.perform(nobody.as(get("/admin/v1/control-room/stream")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void theRollupJobIsQuick() {
        var took = rollups.rollUp(true);
        System.out.println("control room: the rollup job took " + took + " ms on the tests' data");
        assertThat(took).isLessThan(2_000);
    }
}
