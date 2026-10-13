package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.identity.DashboardFigures;
import com.idleo.controlplane.identity.DashboardFigures.Figure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The admin dashboard (ADR 0027): every figure, each from the one module that owns it. */
@ControlPlaneTest
class AdminDashboardTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<DashboardFigures> modules;

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode dashboard(TestAdmins.TestAdmin admin) throws Exception {
        return json.readTree(mvc.perform(admin.as(get("/admin/v1/dashboard")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void everyFigureComesFromExactlyOneModule() {
        assertThat(modules).hasSize(4);
        assertThat(AdminDashboardController.collect(modules)).containsOnlyKeys(Figure.values());
        assertThatThrownBy(() -> AdminDashboardController.collect(List.of(modules.getFirst(), modules.getFirst())))
                .hasMessageContaining("counted by two modules");
        assertThatThrownBy(() -> AdminDashboardController.collect(List.of(() -> Map.of(Figure.REVIEW_QUEUE, 0L))))
                .hasMessageContaining("no module counts");
    }

    @Test
    void showsTheReviewQueueAndTheLatestReconciliationAsTheyStand() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var before = dashboard(admin);
        jdbc.update(
                "insert into metering.review_minutes (session_id, minute_index, minute_at, attempt, reason, created_at)"
                        + " values (?, 0, ?, 1, 'agent_only', ?)",
                UUID.randomUUID(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        var after = dashboard(admin);
        assertThat(after.get("reviewQueue").asLong())
                .isEqualTo(before.get("reviewQueue").asLong() + 1);
        var latest = jdbc.queryForList(
                "select mismatches from billing.reconciliation_runs order by finished_at desc, id desc limit 1",
                Long.class);
        assertThat(after.get("reconciliationMismatches").asLong()).isEqualTo(latest.isEmpty() ? 0 : latest.getFirst());
        for (var field : List.of("hostsOnline", "sessionsRunning", "sessionsToday", "settlementBacklog")) {
            assertThat(after.get(field).asLong()).as(field).isNotNegative();
        }
    }
}
