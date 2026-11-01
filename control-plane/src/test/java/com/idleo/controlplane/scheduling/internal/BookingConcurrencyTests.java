package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.BookingWaits;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** No double booking and no lost hold under concurrency (ADR 0015). */
@ControlPlaneTest
class BookingConcurrencyTests {

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

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SessionMatcher matcher;

    @Autowired
    OfferStore offers;

    @Autowired
    OfferOutcomes outcomes;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @AfterEach
    void disconnect() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    /** The invariants, read in one consistent snapshot; returns how many of the sessions are still working. */
    private int checkInvariants(List<UUID> renters) {
        var snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        return snapshot.execute(status -> {
            assertThat(jdbc.queryForList("""
                            select host_id from sessions.sessions
                            where state in ('MATCHED', 'PROVISIONING', 'RUNNING', 'FINISHING')
                            group by host_id having count(*) > 1
                            """))
                    .as("no host has two live sessions")
                    .isEmpty();
            for (var renter : renters) {
                var reserved = jdbc.queryForObject(
                        "select reserved_paise from billing.wallets where user_id = ?", Long.class, renter);
                var held = jdbc.queryForObject(
                        "select coalesce(sum(amount_paise), 0) from billing.reservations where user_id = ? and status = 'active'",
                        Long.class,
                        renter);
                assertThat(reserved).as("reserved equals the active holds").isEqualTo(held);
            }
            // This test's sessions never run, so only a matched or provisioning one may hold credit. Other
            // tests' running sessions keep theirs until metering closes them (ADR 0017), so they're left out.
            assertThat(jdbc.queryForList("""
                            select s.id from sessions.sessions s
                            join billing.reservations r on r.session_id = s.id and r.status = 'active'
                            where s.renter_id = any(?) and s.state not in ('MATCHED', 'PROVISIONING')
                            """, (Object) renters.toArray(new UUID[0])))
                    .as("only matched or provisioning sessions hold credit")
                    .isEmpty();
            return jdbc.queryForObject(
                    "select count(*) from sessions.sessions where renter_id = any(?) and state in ('REQUESTED', 'MATCHED')",
                    Integer.class,
                    (Object) renters.toArray(new UUID[0]));
        });
    }

    @Test
    void twentyRentersAgainstThreeHostsNeverDoubleBookOrLoseAHold() throws Exception {
        for (int price = 1; price <= 3; price++) {
            hosts.add(TestHosts.ready(mvc, grpcPort, jdbc, price));
        }
        var renters = new ArrayList<TestUser>();
        for (int i = 0; i < 20; i++) {
            var renter = TestUsers.signUp(mvc);
            TestCredit.grant(context, renter.id(), 50_000);
            renters.add(renter);
        }
        var ids = renters.stream().map(TestUser::id).toList();

        var start = new CountDownLatch(1);
        var bookings = new ArrayList<Future<?>>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            for (var renter : renters) {
                bookings.add(pool.submit(() -> {
                    start.await();
                    mvc.perform(
                                    post("/v1/sessions")
                                            .header("Authorization", renter.bearer())
                                            .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000}"))
                            .andExpect(status().isCreated());
                    return null;
                }));
            }
            start.countDown();
            for (var booking : bookings) {
                booking.get();
            }
        }

        // The hosts never answer: offers expire, sessions are re-matched, and all end without a host.
        int working;
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        int checks = 0;
        do {
            working = checkInvariants(ids);
            checks++;
            Thread.sleep(100);
        } while (working > 0 && System.nanoTime() < deadline);

        assertThat(working).as("every session settled").isZero();
        var states = jdbc.queryForList(
                "select state, count(*) as n from sessions.sessions where renter_id = any(?) group by state",
                (Object) ids.toArray(new UUID[0]));
        System.out.println("20 renters, 3 hosts: final states " + states + " after " + checks + " invariant checks");
        assertThat(states).allSatisfy(row -> assertThat(row.get("state")).isEqualTo("NO_HOST_FREE"));
        assertThat(jdbc.queryForObject(
                        "select count(distinct host_id) from scheduling.offers where session_id in (select id from sessions.sessions where renter_id = any(?))",
                        Integer.class,
                        (Object) ids.toArray(new UUID[0])))
                .as("every host was offered work")
                .isEqualTo(3);
        for (var renter : ids) {
            assertThat(jdbc.queryForObject(
                            "select reserved_paise from billing.wallets where user_id = ?", Long.class, renter))
                    .isZero();
        }
    }

    private UUID requestedSession(UUID renter) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                """
                insert into sessions.sessions
                    (id, renter_id, type, state, attempt, template_id, image_digest, vram_needed_mb,
                     max_price_paise_per_min, max_spend_paise, max_runtime_seconds, idempotency_key, request_hash,
                     state_changed_at, version, created_at)
                values (?, ?, 'batch', 'REQUESTED', 1, 'cuda-smoke-test', ?, 1024, 5, 10000, 300, ?, 'hash', ?, 0, ?)
                """,
                id,
                renter,
                "nvidia/cuda:12.8.1-base-ubuntu24.04@sha256:" + "1".repeat(64),
                "direct-" + id,
                now,
                now);
        return id;
    }

    @Test
    void twoSchedulersRacingForOneHostNeverBothBookIt() throws Exception {
        hosts.add(TestHosts.ready(mvc, grpcPort, jdbc, 2));
        for (int round = 0; round < 5; round++) {
            var renterA = TestUsers.signUp(mvc).id();
            var renterB = TestUsers.signUp(mvc).id();
            TestCredit.grant(context, renterA, 10_000);
            TestCredit.grant(context, renterB, 10_000);
            var a = requestedSession(renterA);
            var b = requestedSession(renterB);

            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> {
                    start.await();
                    matcher.match(a);
                    return null;
                });
                var second = pool.submit(() -> {
                    start.await();
                    matcher.match(b);
                    return null;
                });
                start.countDown();
                first.get();
                second.get();
            }

            var states = jdbc.queryForMap(
                    "select (select state from sessions.sessions where id = ?) as a, (select state from sessions.sessions where id = ?) as b",
                    a,
                    b);
            assertThat(states.values()).as("round " + round).containsExactlyInAnyOrder("MATCHED", "NO_HOST_FREE");
            checkInvariants(List.of(renterA, renterB));
            // Free the host for the next round.
            var matched = Map.of(a, renterA, b, renterB).entrySet().stream()
                    .filter(entry -> "MATCHED"
                            .equals(jdbc.queryForObject(
                                    "select state from sessions.sessions where id = ?", String.class, entry.getKey())))
                    .findFirst()
                    .orElseThrow();
            waits().until(
                            () -> "NO_HOST_FREE"
                                            .equals(jdbc.queryForObject(
                                                    "select state from sessions.sessions where id = ?",
                                                    String.class,
                                                    matched.getKey()))
                                    ? true
                                    : null,
                            () -> "session " + matched.getKey() + " never ended without a host");
        }
    }

    /**
     * The scheduled jobs run side by side (SchedulingConfiguration), so offer expiry can meet a host's reply to the
     * same offer. While the reply holds the offer's row, expiry passes it by (SKIP LOCKED) without waiting; once the
     * offer is answered, expiry never touches it. Each offer ends once, and its session is never also declined.
     */
    @Test
    void anOfferBeingAnsweredIsNeitherWaitedOnNorExpiredByTheSweep() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        hosts.add(host);
        var renter = TestUsers.signUp(mvc).id();
        TestCredit.grant(context, renter, 10_000);
        var session = requestedSession(renter);
        matcher.match(session);
        var offerId = jdbc.queryForObject(
                "select id from scheduling.offers where session_id = ? and status = 'pending'", UUID.class, session);

        var replying = new CountDownLatch(1);
        var expired = new CountDownLatch(1);
        var transactions = new TransactionTemplate(transactionManager);
        try (var pool = Executors.newFixedThreadPool(2)) {
            // A reply in flight: the offer's row locked, as onReply holds it.
            var reply = pool.submit(() -> transactions.executeWithoutResult(status -> {
                offers.lock(offerId);
                replying.countDown();
                try {
                    expired.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(replying.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                clock.advance(java.time.Duration.ofMinutes(1));
                var sweep = pool.submit(outcomes::expire);
                sweep.get(5, TimeUnit.SECONDS);
            } finally {
                clock.reset();
                expired.countDown();
            }
            reply.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select status from scheduling.offers where id = ?", String.class, offerId))
                .as("the sweep passed the locked offer by")
                .isEqualTo("pending");

        outcomes.onReply(host.id(), offerId, true, "");
        try {
            clock.advance(java.time.Duration.ofMinutes(1));
            outcomes.expire();
        } finally {
            clock.reset();
        }

        assertThat(jdbc.queryForObject("select status from scheduling.offers where id = ?", String.class, offerId))
                .isEqualTo("accepted");
        assertThat(jdbc.queryForList(
                        "select to_state from sessions.session_events where session_id = ? order by at",
                        String.class,
                        session))
                .doesNotContain("OFFER_DECLINED", "NO_HOST_FREE");
    }
}
