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

/** Availability and quotes (ADR 0019): the scheduler's own filters, and the same check as booking. */
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

    @Test
    void availabilityCountsOnlineAndIdleHostsPerGpuWithTheirPrices() throws Exception {
        var user = renter(50_000);
        host(2);
        host(4);
        var cheap = host(3);

        var row = row(availability(user));
        assertThat(row.get("hostsOnline").asInt()).isEqualTo(3);
        assertThat(row.get("hostsIdle").asInt()).isEqualTo(3);
        assertThat(row.get("pricePaisePerMin").get("min").asInt()).isEqualTo(2);
        assertThat(row.get("pricePaisePerMin").get("median").asInt()).isEqualTo(3);
        assertThat(row.get("pricePaisePerMin").get("max").asInt()).isEqualTo(4);
        assertThat(row.has("hostId")).as("never a host").isFalse();

        // A booking makes one host busy; one gone offline drops out.
        assertThat(call("/v1/sessions", user, body("cuda-smoke-test")).status()).isEqualTo(201);
        cheap.disconnect();
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline
                && row(availability(user)).get("hostsOnline").asInt() != 2) {
            Thread.sleep(100);
        }
        var after = row(availability(user));
        assertThat(after.get("hostsOnline").asInt()).isEqualTo(2);
        assertThat(after.get("hostsIdle").asInt()).isEqualTo(1);
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
                Map.entry(rich, body("echo-input", "params", Map.of("greeting", "hi"))),
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
}
