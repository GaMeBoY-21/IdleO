package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.Session.OfferDeclineReason;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * With Valkey down, session hints go nowhere, and an open event stream still shows changes within its re-read (ADR
 * 0019). Stops its own Valkey, so it never shares a context or containers with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    SessionStreamValkeyOutageTests.DedicatedStores.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestStorageConfiguration.class,
    TestClockConfiguration.class,
    TypedProblemsGuard.class
})
@DirtiesContext
class SessionStreamValkeyOutageTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(15);

    @TestConfiguration(proxyBeanMethods = false)
    static class DedicatedStores {
        @Bean
        @ServiceConnection
        PostgreSQLContainer dedicatedPostgres() {
            return TestcontainersConfiguration.newPostgres();
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> dedicatedValkey() {
            return TestcontainersConfiguration.newValkey();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("dedicatedValkey")
    GenericContainer<?> valkey;

    @LocalGrpcServerPort
    int grpcPort;

    /** The last {@code session} event the stream sent satisfies {@code condition}, within the wait. */
    private static void await(MvcResult stream, Predicate<tools.jackson.databind.JsonNode> condition, String what)
            throws Exception {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            var body = stream.getResponse().getContentAsString();
            var last = body.lastIndexOf("event:session");
            if (last >= 0) {
                var data = body.substring(body.indexOf("data:", last) + 5);
                data = data.substring(0, data.indexOf('\n')).strip();
                if (condition.test(JSON.readTree(data))) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the stream never showed " + what + ": " + body);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void theStreamStillUpdatesWithinItsRereadWhileValkeyIsDown() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        try {
            var renter = TestUsers.signUp(mvc);
            TestCredit.grant(context, renter.id(), 50_000);
            var session = UUID.fromString(JSON.readTree(mvc.perform(post("/v1/sessions")
                                    .header("Authorization", renter.bearer())
                                    .header("Idempotency-Key", "outage-1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, "
                                            + "\"maxSpendPaise\": 10000}"))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .get("id")
                    .asString());
            host.reply(host.awaitOffer(WAIT).getOfferId(), true, OfferDeclineReason.OFFER_DECLINE_REASON_UNSPECIFIED);
            host.ack(host.awaitStart(WAIT));
            var stream = mvc.perform(get("/v1/sessions/" + session + "/events")
                            .header("Authorization", renter.bearer())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            await(stream, view -> view.get("state").asString().equals("PROVISIONING"), "the session on connect");

            valkey.stop();
            // Another instance's change, whose hint can't arrive now.
            jdbc.update("update sessions.sessions set max_spend_paise = 9999 where id = ?", session);
            var changedAt = System.nanoTime();
            await(stream, view -> view.get("maxSpendPaise").asLong() == 9999, "the change while Valkey is down");
            assertThat(Duration.ofNanos(System.nanoTime() - changedAt)).isLessThanOrEqualTo(Duration.ofSeconds(6));

        } finally {
            host.disconnect();
        }
    }
}
