package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * A crash between the session's commit and the scheduler's handling loses nothing (ADR 0015): the event
 * publication stays incomplete and is retried. Its own context, because the matcher is a spy.
 */
@ControlPlaneTest
@DirtiesContext
class BookingRecoveryTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    /** The waits on what matching decides (BookingWaits). */
    private BookingWaits waits() {
        return new BookingWaits(mvc, context);
    }

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    SessionMatcher matcher;

    @Test
    void aListenerThatFailsTheFirstTimeIsRetriedFromTheRegistry() throws Exception {
        doThrow(new IllegalStateException("simulated crash before matching"))
                .doCallRealMethod()
                .when(matcher)
                .match(any());
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);

        var body = mvc.perform(
                        post("/v1/sessions")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", "\"recovery-1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = UUID.fromString(
                JsonMapper.builder().build().readTree(body).get("id").asString());

        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var state = waits().until(
                        () -> {
                            var now = jdbc.queryForObject(
                                    "select state from sessions.sessions where id = ?", String.class, id);
                            return now.equals("REQUESTED") ? null : now;
                        },
                        () -> "session " + id + " stayed REQUESTED");

        // No host is online: once retried, matching ends it without a host.
        assertThat(state).isEqualTo("NO_HOST_FREE");
        verify(matcher, atLeast(2)).match(id);
        // Ending it publishes events of its own (SessionNeverRan), whose listeners finish a moment later: every
        // publication naming the session completes, within the same deadline.
        int incomplete;
        do {
            incomplete = jdbc.queryForObject("""
                    select count(*) from modulith.event_publication
                    where serialized_event like ? and completion_date is null
                    """, Integer.class, "%" + id + "%");
            if (incomplete > 0) {
                Thread.sleep(100);
            }
        } while (incomplete > 0 && System.nanoTime() < deadline);
        assertThat(incomplete).as("the publication completed after the retry").isZero();
    }
}
