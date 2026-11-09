package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The host site's "Demand for your GPU" (ADR 0058): asked, served and unmet, by hour in India, for a GPU's memory. */
@ControlPlaneTest
class DemandTests {

    /** Above every host's memory, so the booking ends without one. */
    private static final int MEMORY = 978_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    private JsonNode demand(TestUsers.TestUser who, int vramMb) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/demand?vramMb=" + vramMb).header("Authorization", who.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void aBookingNoHostCouldTakeCountsAsAskedAndUnmetAtItsHourAndNoSmallerGpuHearsOfIt() throws Exception {
        var host = TestUsers.signUp(mvc);
        var before = demand(host, MEMORY);
        var smaller = demand(host, 8_000);

        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        var answer = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5,"
                                + " \"maxSpendPaise\": 10000, \"vramNeededMb\": " + MEMORY + "}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = UUID.fromString(JSON.readTree(answer).get("id").asString());
        new BookingWaits(mvc, context).state(renter, id, "NO_HOST_FREE");
        int hour = jdbc.queryForObject(
                        "select created_at from sessions.sessions where id = ?", java.sql.Timestamp.class, id)
                .toInstant()
                .atZone(ControlRoom.INDIA)
                .getHour();

        var after = demand(host, MEMORY);
        assertThat(after.get("days").asInt()).isEqualTo(28);
        assertThat(after.get("hours")).hasSize(24);
        assertThat(after.get("asked").asLong()).isEqualTo(before.get("asked").asLong() + 1);
        assertThat(after.get("unmet").asLong()).isEqualTo(before.get("unmet").asLong() + 1);
        assertThat(after.get("served").asLong()).isEqualTo(before.get("served").asLong());
        var at = after.get("hours").get(hour);
        assertThat(at.get("hour").asInt()).isEqualTo(hour);
        assertThat(at.get("asked").asLong())
                .isEqualTo(before.get("hours").get(hour).get("asked").asLong() + 1);
        assertThat(after.get("enough").asBoolean()).isEqualTo(after.get("asked").asLong() >= DemandController.ENOUGH);
        // An 8 GB laptop couldn't have run it, so it isn't that laptop's demand.
        assertThat(demand(host, 8_000).get("unmet").asLong())
                .isEqualTo(smaller.get("unmet").asLong());
    }

    @Test
    void aMemoryThatIsntOneIsRefused() throws Exception {
        mvc.perform(get("/v1/demand?vramMb=0")
                        .header("Authorization", TestUsers.signUp(mvc).bearer()))
                .andExpect(status().isBadRequest());
    }
}
