package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A renter's use this month, by day in India (ADR 0058): settlements less lost time given back. */
@ControlPlaneTest
class UsageTests {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode usage(TestUsers.TestUser who) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/usage").header("Authorization", who.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private UUID session(UUID renter, Instant at) {
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into metering.metered_sessions"
                        + " (session_id, renter_id, started_at, max_spend_paise, created_at, next_window_at)"
                        + " values (?, ?, ?, 10000, ?, ?)",
                id,
                renter,
                Timestamp.from(at),
                Timestamp.from(at),
                Timestamp.from(at.plusSeconds(900)));
        return id;
    }

    private void settled(UUID session, int window, int minutes, long paise, Instant at) {
        jdbc.update(
                "insert into metering.settlements (txn_id, session_id, window_number, minutes, amount_paise,"
                        + " host_share_paise, commission_paise, settled_at) values (?, ?, ?, ?, ?, ?, 0, ?)",
                UUID.randomUUID(),
                session,
                window,
                minutes,
                paise,
                paise,
                Timestamp.from(at));
    }

    @Test
    void eachDayOfTheMonthIsItsChargesLessWhatWasGivenBack() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var now = Instant.now();
        var today = LocalDate.now(IST);
        var session = session(renter.id(), now);
        settled(session, 1, 10, 120, now);
        settled(session, 2, 5, 60, now);
        jdbc.update(
                "insert into metering.waivers (session_id, attempt, host_id, waived_from, refund_txn, refunded_minutes,"
                        + " refunded_paise, host_refund_paise, platform_refund_paise, created_at)"
                        + " values (?, 1, ?, ?, ?, 2, 24, 24, 0, ?)",
                session,
                UUID.randomUUID(),
                Timestamp.from(now),
                UUID.randomUUID(),
                Timestamp.from(now));
        // Someone else's use is never this renter's.
        settled(session(TestUsers.signUp(mvc).id(), now), 1, 30, 999, now);

        var usage = usage(renter);

        assertThat(usage.get("month").asString()).isEqualTo(today.toString().substring(0, 7));
        assertThat(usage.get("days")).hasSize(today.getDayOfMonth());
        var last = usage.get("days").get(today.getDayOfMonth() - 1);
        assertThat(last.get("date").asString()).isEqualTo(today.toString());
        assertThat(last.get("spentPaise").asLong()).isEqualTo(120 + 60 - 24);
        assertThat(last.get("minutes").asLong()).isEqualTo(10 + 5 - 2);
        assertThat(usage.get("spentPaise").asLong()).isEqualTo(156);
        assertThat(usage.get("minutes").asLong()).isEqualTo(13);
    }

    @Test
    void aRenterWithNoUseHasEveryDayAtNothing() throws Exception {
        var usage = usage(TestUsers.signUp(mvc));
        assertThat(usage.get("spentPaise").asLong()).isZero();
        usage.get("days")
                .forEach(day -> assertThat(day.get("spentPaise").asLong()).isZero());
    }
}
