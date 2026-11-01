package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A test that leaves a session waiting for a host (or offered to one that never answers) must not reach the next test's
 * hosts: they would count as busy and a quote would find them one short. The first test leaves such a session behind, on
 * purpose and without cancelling it; the second, which runs right after, starts clean and its new host is offered nothing.
 */
@ControlPlaneTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NoSessionsLeftWaitingTests {

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

    @LocalGrpcServerPort
    int grpcPort;

    private ConnectedHost host;

    @AfterEach
    void disconnect() {
        if (host != null) {
            host.disconnect();
        }
    }

    @Test
    @Order(1)
    void aTestThatLeavesASessionOfferedToAHostThatNeverAnswers() throws Exception {
        host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, "
                                + "\"maxSpendPaise\": 10000}"))
                .andExpect(status().isCreated());
        assertThat(waits().offer(host).getOfferId()).isNotBlank();
    }

    @Test
    @Order(2)
    void thenTheNextTestStartsWithNothingWaitingAndItsHostIsOfferedNothing() throws Exception {
        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.sessions where state in ('REQUESTED', 'MATCHED', 'PROVISIONING')",
                        Integer.class))
                .as("sessions waiting for a host or being offered to one")
                .isZero();

        host = TestHosts.ready(mvc, grpcPort, jdbc, 2);

        assertThat(host.stream().receives(message -> message.hasOffer(), Duration.ofSeconds(3)))
                .as("an offer for an earlier test's session")
                .isFalse();
    }
}
