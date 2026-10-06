package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.OfferDeclineReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** From "renter books" to "host accepts" (ADR 0015), against real agent streams. */
@ControlPlaneTest
class BookingTests {

    static final Duration WAIT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OfferDeclineReason NONE = OfferDeclineReason.OFFER_DECLINE_REASON_UNSPECIFIED;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @AfterEach
    void disconnect() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    private ConnectedHost host(int price) throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, price);
        hosts.add(host);
        return host;
    }

    private TestUser renter(long paise) throws Exception {
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), paise);
        return renter;
    }

    private UUID book(TestUser renter, int maxPrice) throws Exception {
        var body = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": %d, \"maxSpendPaise\": 10000}"
                                        .formatted(maxPrice)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private JsonNode session(TestUser renter, UUID id) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/sessions/" + id).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode awaitState(TestUser renter, UUID id, String state) throws Exception {
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

    private JsonNode wallet(TestUser renter) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void anAcceptedOfferHoldsCreditUntilTheSessionFailsToStart() throws Exception {
        var host = host(2);
        var renter = renter(50_000);

        var id = book(renter, 5);
        var offer = host.awaitOffer(WAIT);
        assertThat(offer.getSpec().getImage()).contains("@sha256:");
        assertThat(offer.getExpiresIn().getSeconds()).isEqualTo(2);
        var matched = awaitState(renter, id, "MATCHED");
        assertThat(matched.get("hostId").asString()).isEqualTo(host.id().toString());
        assertThat(matched.get("pricePaisePerMin").asInt()).isEqualTo(2);
        assertThat(wallet(renter).get("reservedPaise").asLong()).isEqualTo(60);

        host.reply(offer.getOfferId(), true, NONE);
        host.stream().await(OpenStreamResponse::hasAck, WAIT);
        awaitState(renter, id, "PROVISIONING");
        assertThat(wallet(renter).get("reservedPaise").asLong()).isEqualTo(60);

        var failed = awaitState(renter, id, "FAILED");
        assertThat(failed.get("events").findValuesAsString("reason")).contains("start_timeout");
        assertThat(wallet(renter).get("availablePaise").asLong()).isEqualTo(50_000);
        assertThat(wallet(renter).get("reservedPaise").asLong()).isZero();
    }

    @Test
    void aDeclineGoesToTheNextCandidate() throws Exception {
        var cheap = host(1);
        var dearer = host(2);
        var renter = renter(50_000);

        var id = book(renter, 5);
        var first = cheap.awaitOffer(WAIT);
        cheap.reply(first.getOfferId(), false, OfferDeclineReason.OFFER_DECLINE_REASON_OWNER_ACTIVE);
        var second = dearer.awaitOffer(WAIT);

        var matched = awaitState(renter, id, "MATCHED");
        assertThat(matched.get("hostId").asString()).isEqualTo(dearer.id().toString());
        assertThat(second.getOfferId()).isNotEqualTo(first.getOfferId());
        assertThat(jdbc.queryForObject(
                        "select decline_reason from scheduling.offers where id = ?::uuid",
                        String.class,
                        first.getOfferId()))
                .isEqualTo("owner_active");
        assertThat(wallet(renter).get("reservedPaise").asLong()).isEqualTo(60);
    }

    @Test
    void threeDeclinesMeanNoHostIsFreeAndTheHoldIsReleased() throws Exception {
        var a = host(1);
        var b = host(2);
        var c = host(3);
        var renter = renter(50_000);

        var id = book(renter, 5);
        for (var host : List.of(a, b, c)) {
            host.reply(host.awaitOffer(WAIT).getOfferId(), false, OfferDeclineReason.OFFER_DECLINE_REASON_TOO_HOT);
        }

        var ended = awaitState(renter, id, "NO_HOST_FREE");
        assertThat(ended.get("events").findValuesAsString("reason")).last().isEqualTo("offers_exhausted");
        assertThat(wallet(renter).get("reservedPaise").asLong()).isZero();
        assertThat(wallet(renter).get("availablePaise").asLong()).isEqualTo(50_000);
    }

    @Test
    void anExpiredOfferGoesToTheNextCandidateAndALateAcceptIsIgnored() throws Exception {
        var silent = host(1);
        var other = host(2);
        var renter = renter(50_000);

        var id = book(renter, 5);
        var ignored = silent.awaitOffer(WAIT);
        other.awaitOffer(WAIT);
        var matched = awaitState(renter, id, "MATCHED");
        assertThat(matched.get("hostId").asString()).isEqualTo(other.id().toString());
        assertThat(jdbc.queryForObject(
                        "select status from scheduling.offers where id = ?::uuid", String.class, ignored.getOfferId()))
                .isEqualTo("expired");

        silent.reply(ignored.getOfferId(), true, NONE);
        silent.stream().await(OpenStreamResponse::hasAck, WAIT);
        assertThat(session(renter, id).get("hostId").asString())
                .isEqualTo(other.id().toString());
        assertThat(session(renter, id).get("state").asString()).isEqualTo("MATCHED");
    }

    @Test
    void acceptingAnOfferForACancelledSessionIsIgnored() throws Exception {
        var host = host(2);
        var renter = renter(50_000);

        var id = book(renter, 5);
        var offer = host.awaitOffer(WAIT);
        mvc.perform(post("/v1/sessions/" + id + "/cancel").header("Authorization", renter.bearer()))
                .andExpect(status().isOk());
        host.reply(offer.getOfferId(), true, NONE);
        host.stream().await(OpenStreamResponse::hasAck, WAIT);

        assertThat(session(renter, id).get("state").asString()).isEqualTo("CANCELLED");
        assertThat(wallet(renter).get("reservedPaise").asLong()).isZero();
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!"withdrawn"
                        .equals(jdbc.queryForObject(
                                "select status from scheduling.offers where id = ?::uuid",
                                String.class,
                                offer.getOfferId()))
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForObject(
                        "select status from scheduling.offers where id = ?::uuid", String.class, offer.getOfferId()))
                .isEqualTo("withdrawn");
    }

    @Test
    void noOnlineHostMeansNoHostFreeAtOnce() throws Exception {
        var renter = renter(50_000);

        var id = book(renter, 5);

        var ended = awaitState(renter, id, "NO_HOST_FREE");
        assertThat(ended.get("events").findValuesAsString("reason")).last().isEqualTo("no_candidate");
        assertThat(wallet(renter).get("reservedPaise").asLong()).isZero();
    }
}
