package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * The host score in matching, against real agent streams: when price is equal, the host with the better reliability,
 * the faster line or the warm cache gets the offer, and what an agent says it has cached is read with care.
 */
@ControlPlaneTest
class HostScoreMatchingTests {

    private static final Duration WAIT = Duration.ofSeconds(10);
    /** The image of {@code cuda-smoke-test} in templates/development.yaml. */
    private static final String SMOKE_TEST_DIGEST =
            "sha256:133c78a0575303be34164d0b90137a042172bdf60696af01a3c424ab402d86e2";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AgentPresence presence;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @AfterEach
    void disconnect() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    /**
     * Two otherwise equal hosts at one price, the one a tie would pick (the smaller id) first. The second is the
     * one these tests favour, so a pass means the score decided, not the tie-break.
     */
    private List<ConnectedHost> twoEqualHosts() throws Exception {
        var pair = new ArrayList<ConnectedHost>();
        pair.add(TestHosts.ready(mvc, grpcPort, jdbc, 2));
        pair.add(TestHosts.ready(mvc, grpcPort, jdbc, 2));
        hosts.addAll(pair);
        pair.sort(Comparator.comparing(ConnectedHost::id));
        return pair;
    }

    private void score(ConnectedHost host, double reliability, double bandwidth) {
        jdbc.update("""
                insert into hosts.host_scores (host_id, reliability, bandwidth, session_score, self_test_score, penalty,
                    sessions_finished, sessions_lost, self_tests_passed, self_tests_failed, reports_upheld, flags,
                    computed_at)
                values (?, ?, ?, 0.5, 0.5, 0, 0, 0, 0, 0, 0, 0, ?)
                on conflict (host_id) do update set reliability = excluded.reliability, bandwidth = excluded.bandwidth
                """, host.id(), reliability, bandwidth, Timestamp.from(Instant.now()));
    }

    private void book() throws Exception {
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, "
                                + "\"maxSpendPaise\": 10000}"))
                .andExpect(status().isCreated());
    }

    private void awaitCached(ConnectedHost host, String digest) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var agent = presence.live(List.of(host.id())).get(host.id());
            if (agent != null && agent.cachedImages().contains(digest)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("host " + host.id() + " never showed " + digest + " as cached");
    }

    @Test
    void aWarmCacheDecidesBetweenOtherwiseEqualHosts() throws Exception {
        var pair = twoEqualHosts();
        var cold = pair.get(0);
        var warm = pair.get(1);
        warm.cachedImages().set(List.of(SMOKE_TEST_DIGEST));
        awaitCached(warm, SMOKE_TEST_DIGEST);

        book();

        var offer = warm.awaitOffer(WAIT);
        assertThat(offer.getSpec().getImage()).endsWith(SMOKE_TEST_DIGEST);
        // The next host is only offered the session once the warm one's offer lapses, two seconds on.
        assertThat(cold.stream().receives(message -> message.hasOffer(), Duration.ofMillis(300)))
                .isFalse();
    }

    @Test
    void theMoreReliableHostGetsTheOfferAtTheSamePrice() throws Exception {
        var pair = twoEqualHosts();
        score(pair.get(0), 0.2, 0.5);
        score(pair.get(1), 0.9, 0.5);

        book();

        pair.get(1).awaitOffer(WAIT);
        assertThat(pair.get(0).stream().receives(message -> message.hasOffer(), Duration.ofMillis(300)))
                .isFalse();
    }

    @Test
    void theFasterLineGetsTheOfferAtTheSamePrice() throws Exception {
        var pair = twoEqualHosts();
        score(pair.get(0), 0.5, 0.1);
        score(pair.get(1), 0.5, 1.0);

        book();

        pair.get(1).awaitOffer(WAIT);
    }

    @Test
    void whatAnAgentSaysItHasCachedIsKeptOnlyWhenItIsADigest() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        hosts.add(host);
        var valid = "sha256:" + "a".repeat(64);
        var many = new ArrayList<String>();
        many.add(valid);
        many.add("registry.example/job:latest");
        many.add("sha256:short");
        many.add(valid);
        host.cachedImages().set(many);

        awaitCached(host, valid);

        assertThat(presence.live(List.of(host.id())).get(host.id()).cachedImages())
                .containsExactly(valid);

        // And no more than a catalog's worth: the rest are dropped, the heartbeat still counts.
        var lots = new ArrayList<String>();
        for (int i = 0; i < 200; i++) {
            lots.add("sha256:" + String.format("%064x", i));
        }
        host.cachedImages().set(lots);
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (presence.live(List.of(host.id())).get(host.id()).cachedImages().size() != 64
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(presence.live(List.of(host.id())).get(host.id()).cachedImages())
                .hasSize(64);
    }
}
