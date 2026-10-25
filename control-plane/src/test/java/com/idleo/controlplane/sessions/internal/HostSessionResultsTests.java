package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.hosts.HostSessionResults;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionEnded;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What counts for and against a host's score from its sessions: the sessions it finished, and the ones it lost. A
 * renter's stop, a job that failed, and an owner coming back to the machine never count against it.
 */
@ControlPlaneTest
class HostSessionResultsTests {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    HostSessionResults results;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @LocalGrpcServerPort
    int grpcPort;

    /** A session whose one attempt ran on {@code host}; {@code lostReason} is null for an attempt that wasn't lost. */
    private UUID session(UUID host, String state, String lostReason, Instant endedAt) {
        var id = UUID.randomUUID();
        var at = Timestamp.from(endedAt);
        jdbc.update("""
                insert into sessions.sessions (id, renter_id, host_id, type, state, attempt, template_id, image_digest,
                    vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds,
                    idempotency_key, request_hash, state_changed_at, version, created_at)
                values (?, ?, ?, 'batch', ?, 1, 'cuda-smoke-test', 'img@sha256:abc', 1000, 5, 2, 1000, 3600,
                    ?, 'hash', ?, 1, ?)
                """, id, UUID.randomUUID(), host, state, id.toString(), at, at);
        jdbc.update("""
                insert into sessions.attempts (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,
                    created_at, running_at, ended_at, price_paise_per_min, lost_reason)
                values (?, 1, ?, array['GPU-1'], ?, 0, ?, ?, ?, 2, ?)
                """, id, host, UUID.randomUUID(), at, at, at, lostReason);
        return id;
    }

    private List<HostSessionResults.Result> since(UUID host, Duration ago) {
        return results.since(List.of(host), NOW.minus(ago));
    }

    @Test
    void aSessionTheHostRanToTheEndIsFinishedWhoeverStoppedIt() {
        var host = UUID.randomUUID();
        session(host, "COMPLETED", null, NOW.minus(Duration.ofHours(2)));
        // A renter's own stop completes the session too ("stopped"): it counts for the host, never against it.
        session(host, "COMPLETED", null, NOW.minus(Duration.ofHours(1)));

        assertThat(since(host, Duration.ofDays(30)))
                .hasSize(2)
                .allSatisfy(result -> assertThat(result.finished()).isTrue());
    }

    @Test
    void anAttemptLostToTheHostCountsAgainstIt() {
        var host = UUID.randomUUID();
        session(host, "HOST_LOST", "lease_lapsed", NOW.minus(Duration.ofHours(5)));
        session(host, "HOST_LOST", "missing_after_reconnect", NOW.minus(Duration.ofHours(4)));
        session(host, "HOST_LOST", "lease_expired_on_host", NOW.minus(Duration.ofHours(3)));
        session(host, "HOST_LOST", "stopped_on_host", NOW.minus(Duration.ofHours(2)));

        assertThat(since(host, Duration.ofDays(30)))
                .hasSize(4)
                .allSatisfy(result -> assertThat(result.finished()).isFalse());
    }

    @Test
    void anOwnerComingBackAJobFailingAndARenterCancellingNeverCountAgainstTheHost() {
        var host = UUID.randomUUID();
        // The owner took the machine back after a checkpoint, and the session went on elsewhere.
        session(host, "HOST_LOST", SessionProgress.OWNER_RETURNED, NOW.minus(Duration.ofHours(5)));
        session(host, "FAILED", null, NOW.minus(Duration.ofHours(4)));
        session(host, "CANCELLED", null, NOW.minus(Duration.ofHours(3)));

        assertThat(since(host, Duration.ofDays(30))).isEmpty();
    }

    @Test
    void onlyTheWindowAndTheAskedForHostsCount() {
        var host = UUID.randomUUID();
        var other = UUID.randomUUID();
        session(host, "COMPLETED", null, NOW.minus(Duration.ofDays(40)));
        session(host, "COMPLETED", null, NOW.minus(Duration.ofDays(2)));
        session(other, "COMPLETED", null, NOW.minus(Duration.ofDays(1)));

        assertThat(since(host, Duration.ofDays(30))).hasSize(1);
        assertThat(results.since(List.of(), NOW.minus(Duration.ofDays(30)))).isEmpty();
    }

    @Test
    void aSessionEndingOrBeingLostWorksTheHostsScoreOutAgain() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var host = agent.hostId;
        assertThat(scores(host)).isZero();

        var finished = session(host, "COMPLETED", null, NOW);
        transactions.executeWithoutResult(status -> events.publishEvent(new SessionEnded(
                finished, UUID.randomUUID(), host, "COMPLETED", NOW.minus(Duration.ofMinutes(5)), NOW)));
        awaitScore(host, 1, 0);

        var lost = session(host, "HOST_LOST", "lease_lapsed", NOW);
        transactions.executeWithoutResult(status -> events.publishEvent(new AttemptLost(lost, 1)));
        awaitScore(host, 1, 1);
    }

    private int scores(UUID host) {
        return jdbc.queryForObject("select count(*) from hosts.host_scores where host_id = ?", Integer.class, host);
    }

    private void awaitScore(UUID host, int finished, int lost) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var rows = jdbc.queryForList(
                    "select sessions_finished, sessions_lost from hosts.host_scores where host_id = ?", host);
            if (!rows.isEmpty()
                    && ((Number) rows.getFirst().get("sessions_finished")).intValue() == finished
                    && ((Number) rows.getFirst().get("sessions_lost")).intValue() == lost) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("host " + host + " never reached " + finished + " finished and " + lost + " lost");
    }
}
