package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Availability and quotes (ADR 0019): the scheduler's own filters, and the same check as booking; a ceiling the server
 * sets when the booking leaves it out, and a quote that may leave out the input (ADR 0021).
 */
@ControlPlaneTest
class QuoteTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();
    private final List<Map.Entry<TestUser, String>> booked = new ArrayList<>();

    @AfterEach
    void tidy() throws Exception {
        for (var session : booked) {
            mvc.perform(post("/v1/sessions/" + session.getValue() + "/cancel")
                    .header("Authorization", session.getKey().bearer()));
        }
        booked.clear();
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    private ConnectedHost host(int price) throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, price);
        hosts.add(host);
        return host;
    }

    private TestUser renter(long credit) throws Exception {
        var renter = TestUsers.signUp(mvc);
        if (credit > 0) {
            TestCredit.grant(context, renter.id(), credit);
        }
        return renter;
    }

    private record Answer(int status, JsonNode body) {}

    private Answer call(String path, TestUser renter, Map<String, Object> body) throws Exception {
        var request = post(path)
                .header("Authorization", renter.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (path.equals("/v1/sessions")) {
            request = request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        var answer = new Answer(response.getStatus(), JSON.readTree(response.getContentAsString()));
        if (path.equals("/v1/sessions") && answer.status() == 201) {
            booked.add(Map.entry(renter, answer.body().get("id").asString()));
        }
        return answer;
    }

    private static Map<String, Object> body(String templateId, Object... more) {
        var body = new LinkedHashMap<String, Object>();
        body.put("templateId", templateId);
        body.put("maxPricePaisePerMin", 5);
        body.put("maxSpendPaise", 10_000);
        for (int i = 0; i < more.length; i += 2) {
            body.put((String) more[i], more[i + 1]);
        }
        return body;
    }

    private JsonNode availability(TestUser user) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/availability").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static JsonNode row(JsonNode availability) {
        for (var row : availability.get("gpus")) {
            if (row.get("gpuModel").asString().equals(TestAgent.GPU_MODEL)
                    && row.get("vramMb").asInt() == 12282) {
                return row;
            }
        }
        return null;
    }

    private int count(TestUser user, String field) throws Exception {
        var row = row(availability(user));
        return row == null ? 0 : row.get(field).asInt();
    }

    private void awaitCount(TestUser user, String field, int wanted) throws Exception {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (count(user, field) != wanted) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(field + " never became " + wanted + ": " + availability(user));
            }
            Thread.sleep(100);
        }
    }

    @Test
    void availabilityCountsOnlineAndIdleHostsPerGpuWithTheirPrices() throws Exception {
        var user = renter(50_000);
        // Other tests' hosts may still be online in this shared context: count from where things stand.
        int online = count(user, "hostsOnline");
        int idle = count(user, "hostsIdle");
        host(2);
        host(4);
        var cheap = host(3);

        awaitCount(user, "hostsOnline", online + 3);
        awaitCount(user, "hostsIdle", idle + 3);
        var row = row(availability(user));
        assertThat(row.get("pricePaisePerMin").get("min").asInt()).isLessThanOrEqualTo(2);
        assertThat(row.get("pricePaisePerMin").get("max").asInt()).isGreaterThanOrEqualTo(4);
        if (online == 0) {
            assertThat(row.get("pricePaisePerMin").get("median").asInt()).isEqualTo(3);
        }
        assertThat(row.has("hostId")).as("never a host").isFalse();

        // A booking makes one host busy; one gone offline drops out.
        var booked = call("/v1/sessions", user, body("cuda-smoke-test"));
        assertThat(booked.status()).isEqualTo(201);
        awaitCount(user, "hostsIdle", idle + 2);
        cheap.disconnect();
        awaitCount(user, "hostsOnline", online + 2);
    }

    @Test
    void aQuoteGivesTheHoldTheCreditAndTheHostsThatFitAndReservesNothing() throws Exception {
        var renter = renter(50_000);
        host(2);
        host(4);
        host(9);

        var quote = call("/v1/session-quotes", renter, body("cuda-smoke-test"));

        assertThat(quote.status()).isEqualTo(200);
        assertThat(quote.body().get("holdPaise").asLong())
                .as("30 minutes at 5 paise")
                .isEqualTo(150);
        assertThat(quote.body().get("availablePaise").asLong()).isEqualTo(50_000);
        assertThat(quote.body().get("enoughCredit").asBoolean()).isTrue();
        assertThat(quote.body().get("hostsFitNow").asInt())
                .as("the 9-paise host is above the maximum")
                .isEqualTo(2);
        assertThat(quote.body().get("pricePaisePerMin").get("min").asInt()).isEqualTo(2);
        assertThat(quote.body().get("pricePaisePerMin").get("max").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.sessions where renter_id = ?", Integer.class, renter.id()))
                .isZero();
        var wallet = JSON.readTree(mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(wallet.get("reservedPaise").asLong()).isZero();

        var big = call("/v1/session-quotes", renter, body("cuda-smoke-test", "vramNeededMb", 24_000));
        assertThat(big.body().get("hostsFitNow").asInt()).isZero();
        assertThat(big.body().get("pricePaisePerMin").isNull()).isTrue();
    }

    @Test
    void aRequestQuotesAndBooksTheSameWay() throws Exception {
        host(2);
        var rich = renter(50_000);
        var broke = renter(0);
        var cases = List.of(
                Map.entry(rich, body("cuda-smoke-test")),
                Map.entry(rich, body("no-such-template")),
                Map.entry(rich, body("cuda-smoke-test", "vramNeededMb", 1)),
                Map.entry(rich, body("cuda-smoke-test", "input", Map.of("url", "https://example.com/a"))),
                Map.entry(
                        rich,
                        body(
                                "echo-input",
                                "params",
                                Map.of("repeat", 9),
                                "input",
                                Map.of("url", "https://example.com/a"))),
                Map.entry(
                        rich,
                        body(
                                "echo-input",
                                "params",
                                Map.of("greeting", "hi"),
                                "input",
                                Map.of("url", "http://example.com/a"))),
                Map.entry(
                        rich,
                        body(
                                "echo-input",
                                "params",
                                Map.of("greeting", "hi"),
                                "input",
                                Map.of("uploadId", UUID.randomUUID()))),
                Map.entry(
                        rich,
                        body(
                                "echo-input",
                                "params",
                                Map.of("greeting", "hi"),
                                "input",
                                Map.of("url", "https://example.com/a"))),
                Map.entry(broke, body("cuda-smoke-test")),
                Map.entry(
                        broke,
                        body(
                                "echo-input",
                                "params",
                                Map.of("greeting", "hi"),
                                "input",
                                Map.of("url", "https://example.com/a"))));

        for (var request : cases) {
            var quote = call("/v1/session-quotes", request.getKey(), request.getValue());
            var booking = call("/v1/sessions", request.getKey(), request.getValue());
            var what = request.getValue().toString();
            if (quote.status() == 200) {
                if (quote.body().get("enoughCredit").asBoolean()) {
                    assertThat(booking.status()).as(what).isEqualTo(201);
                } else {
                    assertThat(booking.status()).as(what).isEqualTo(402);
                    assertThat(booking.body().get("type").asString()).isEqualTo("/problems/insufficient-credit");
                }
            } else {
                assertThat(booking.status()).as(what).isEqualTo(quote.status());
                assertThat(booking.body().get("type"))
                        .as(what)
                        .isEqualTo(quote.body().get("type"));
                assertThat(booking.body().path("errors"))
                        .as(what)
                        .isEqualTo(quote.body().path("errors"));
            }
        }
    }

    private static Map<String, Object> withoutCeiling(Map<String, Object> body) {
        body.remove("maxPricePaisePerMin");
        return body;
    }

    @Test
    void withoutACeilingTheQuoteAndTheBookingUseTheHighestPriceAmongTheGpusThatFitNow() throws Exception {
        var renter = renter(50_000);
        host(2);
        host(997);

        var quote = call("/v1/session-quotes", renter, withoutCeiling(body("cuda-smoke-test")));
        assertThat(quote.status()).isEqualTo(200);
        assertThat(quote.body().get("ceilingPaisePerMin").asInt()).isEqualTo(997);
        assertThat(quote.body().get("holdPaise").asLong())
                .as("30 minutes at the ceiling, up to the cap")
                .isEqualTo(Math.min(30L * 997, 10_000));
        assertThat(quote.body().get("pricePaisePerMin").get("max").asInt()).isEqualTo(997);

        var booking = call("/v1/sessions", renter, withoutCeiling(body("cuda-smoke-test")));
        assertThat(booking.status()).isEqualTo(201);
        assertThat(booking.body().get("maxPricePaisePerMin").asInt())
                .as("the session keeps the ceiling it was given")
                .isEqualTo(997);
    }

    @Test
    void theServersCeilingDecidesWhetherTheCreditIsEnough() throws Exception {
        host(2);
        host(400);
        // 30 minutes at 400 paise is 12,000: more than this renter has, though the cheap host alone would fit.
        var renter = renter(5_000);
        var body = withoutCeiling(body("cuda-smoke-test", "maxSpendPaise", 50_000));

        var quote = call("/v1/session-quotes", renter, body);
        assertThat(quote.body().get("holdPaise").asLong()).isEqualTo(12_000);
        assertThat(quote.body().get("enoughCredit").asBoolean()).isFalse();
        var booking = call("/v1/sessions", renter, body);
        assertThat(booking.status()).isEqualTo(402);
        assertThat(booking.body().get("type").asString()).isEqualTo("/problems/insufficient-credit");
    }

    @Test
    void withoutACeilingAndNoGpuThatFitsNothingIsCreated() throws Exception {
        var renter = renter(50_000);
        host(2);
        var body = withoutCeiling(body("cuda-smoke-test", "vramNeededMb", 900_000));

        var quote = call("/v1/session-quotes", renter, body);
        var booking = call("/v1/sessions", renter, body);

        for (var refused : List.of(quote, booking)) {
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.body().get("type").asString()).isEqualTo("/problems/no-gpu-free");
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.sessions where renter_id = ?", Integer.class, renter.id()))
                .isZero();
        // A ceiling the renter sets still books, and waits for a host as before.
        var withCeiling = call("/v1/session-quotes", renter, body("cuda-smoke-test", "vramNeededMb", 900_000));
        assertThat(withCeiling.status()).isEqualTo(200);
        assertThat(withCeiling.body().get("hostsFitNow").asInt()).isZero();
    }

    @Test
    void aQuoteMayLeaveOutTheInputButBookingMayNot() throws Exception {
        host(2);
        var renter = renter(50_000);
        var noInput = body("echo-input", "params", Map.of("greeting", "hi"));

        var quote = call("/v1/session-quotes", renter, noInput);
        assertThat(quote.status()).as("a price while the upload is still going").isEqualTo(200);
        assertThat(quote.body().get("holdPaise").asLong()).isEqualTo(150);

        var booking = call("/v1/sessions", renter, noInput);
        assertThat(booking.status()).isEqualTo(400);
        assertThat(booking.body().get("type").asString()).isEqualTo("/problems/input-required");

        // An input that is given is still checked in full.
        var notReady = call(
                "/v1/session-quotes",
                renter,
                body("echo-input", "params", Map.of("greeting", "hi"), "input", Map.of("uploadId", UUID.randomUUID())));
        assertThat(notReady.status()).isEqualTo(404);
    }
}
