package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** A booking that ended without a host (ADR 0056): recorded, counted against what was asked, and findable from its cell. */
@ControlPlaneTest
class UnmetDemandTests {

    /** Above every class and every host's memory, so no host can take it and this test's cell is its own. */
    private static final int MEMORY_NOBODY_HAS = 977_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    SessionsControlRoom room;

    @Test
    void aBookingNoHostCouldTakeIsRecordedAndShownAgainstWhatWasAskedAndServed() throws Exception {
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        var answer = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000,"
                                        .concat(" \"vramNeededMb\": " + MEMORY_NOBODY_HAS + "}")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = UUID.fromString(JSON.readTree(answer).get("id").asString());
        new BookingWaits(mvc, context).state(renter, id, "NO_HOST_FREE");

        var created = jdbc.queryForObject(
                "select created_at from sessions.sessions where id = ?", java.sql.Timestamp.class, id);
        int hour = created.toInstant().atZone(ControlRoom.INDIA).getHour();
        var row = jdbc.queryForMap(
                "select gpu_class_mb, asked_hour, why from sessions.unmet_bookings where session_id = ?", id);
        assertThat(row.get("gpu_class_mb")).isEqualTo(MEMORY_NOBODY_HAS);
        assertThat(((Number) row.get("asked_hour")).intValue()).isEqualTo(hour);
        assertThat(row.get("why")).isEqualTo("no_candidate");

        var cell = room.demand(clock.instant()).stream()
                .filter(each -> each.classMb() == MEMORY_NOBODY_HAS && each.hour() == hour)
                .findFirst()
                .orElseThrow();
        assertThat(cell.gpu()).isEqualTo("977 GB");
        assertThat(cell.asked()).isEqualTo(1);
        assertThat(cell.served()).isZero();
        assertThat(cell.gap()).isEqualTo(1);

        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var behind = mvc.perform(owner.as(get("/admin/v1/sessions")
                        .param("demandClass", String.valueOf(MEMORY_NOBODY_HAS))
                        .param("demandHour", String.valueOf(hour))
                        .param("state", "NO_HOST_FREE")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(behind).contains(id.toString());
        var anotherHour = mvc.perform(owner.as(get("/admin/v1/sessions")
                        .param("demandClass", String.valueOf(MEMORY_NOBODY_HAS))
                        .param("demandHour", String.valueOf((hour + 1) % 24))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(anotherHour).doesNotContain(id.toString());
        var served = mvc.perform(owner.as(get("/admin/v1/sessions")
                        .param("demandClass", String.valueOf(MEMORY_NOBODY_HAS))
                        .param("served", "true")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(served).doesNotContain(id.toString());
    }
}
