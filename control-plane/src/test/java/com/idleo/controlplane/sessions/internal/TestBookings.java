package com.idleo.controlplane.sessions.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.Session.OfferDeclineReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Booked sessions for the sessions module's tests, against real agent streams. Disconnect it when done. */
final class TestBookings implements AutoCloseable {

    static final Duration WAIT = Duration.ofSeconds(10);
    static final OfferDeclineReason ACCEPT = OfferDeclineReason.OFFER_DECLINE_REASON_UNSPECIFIED;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final ApplicationContext context;
    private final JdbcTemplate jdbc;
    private final int grpcPort;
    private final List<ConnectedHost> hosts = new ArrayList<>();

    TestBookings(MockMvc mvc, ApplicationContext context, JdbcTemplate jdbc, int grpcPort) {
        this.mvc = mvc;
        this.context = context;
        this.jdbc = jdbc;
        this.grpcPort = grpcPort;
    }

    /** A host the renter booked, whose agent has accepted the offer. */
    record Booked(ConnectedHost host, TestUser renter, UUID sessionId) {}

    /** A connected, listed, active host at 2 paise a minute. */
    ConnectedHost host() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        hosts.add(host);
        return host;
    }

    TestUser renter() throws Exception {
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        return renter;
    }

    UUID book(TestUser renter) throws Exception {
        var body = mvc.perform(
                        post("/v1/sessions")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    /** Books a new host for a new renter; the host accepts. */
    Booked bookAndAccept() throws Exception {
        var host = host();
        var renter = renter();
        var sessionId = book(renter);
        host.reply(host.awaitOffer(WAIT).getOfferId(), true, ACCEPT);
        return new Booked(host, renter, sessionId);
    }

    JsonNode session(TestUser renter, UUID id) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/sessions/" + id).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    JsonNode awaitState(TestUser renter, UUID id, String state) throws Exception {
        var deadline = System.nanoTime() + WAIT.toNanos();
        JsonNode session;
        do {
            session = session(renter, id);
            if (session.get("state").asString().equals(state)) {
                return session;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("session " + id + " never reached " + state + "; it's " + session);
    }

    @Override
    public void close() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }
}
