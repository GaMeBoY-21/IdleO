package com.idleo.controlplane.sessions.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.Session.OfferDeclineReason;
import gpu.agent.v1.Session.SessionError;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<UUID, TestUser> owners = new LinkedHashMap<>();

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
        return renter(50_000);
    }

    TestUser renter(long credit) throws Exception {
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), credit);
        return renter;
    }

    UUID book(TestUser renter) throws Exception {
        return book(renter, 5, 10_000);
    }

    UUID book(TestUser renter, int maxPrice, long maxSpend) throws Exception {
        var body = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": %d, \"maxSpendPaise\": %d}"
                                        .formatted(maxPrice, maxSpend)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = UUID.fromString(JSON.readTree(body).get("id").asString());
        owners.put(id, renter);
        return id;
    }

    /**
     * Books the renter's own image ({@code custom-image}, ADR 0042) with {@code params} on a new host; the host accepts.
     */
    Booked bookOwnImageAndAccept(TestUser renter, UUID imageId, String params) throws Exception {
        var host = host();
        var body = mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"templateId\": \"custom-image\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000, "
                                        + "\"vramNeededMb\": 8192, \"imageId\": \"" + imageId + "\", \"params\": "
                                        + params + "}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var sessionId = UUID.fromString(JSON.readTree(body).get("id").asString());
        owners.put(sessionId, renter);
        host.reply(offerFor(host, sessionId), true, ACCEPT);
        return new Booked(host, renter, sessionId);
    }

    /** Books a new host for a new renter; the host accepts. */
    Booked bookAndAccept() throws Exception {
        var host = host();
        var renter = renter();
        var sessionId = book(renter);
        host.reply(offerFor(host, sessionId), true, ACCEPT);
        return new Booked(host, renter, sessionId);
    }

    /**
     * The offer of this session on this host. A new host is offered whatever session is waiting for one, and an earlier test's
     * session that lost its host waits for the next (a session that already ran isn't ended for want of a host), so the first offer
     * a new host hears may be that one's: it's declined, and the one this test booked comes after.
     */
    private String offerFor(ConnectedHost host, UUID sessionId) throws InterruptedException {
        while (true) {
            var offerId = host.awaitOffer(WAIT).getOfferId();
            var ours = jdbc.queryForObject(
                    "select session_id = ? from scheduling.offers where id = ?",
                    Boolean.class,
                    sessionId,
                    UUID.fromString(offerId));
            if (Boolean.TRUE.equals(ours)) {
                return offerId;
            }
            host.reply(offerId, false, OfferDeclineReason.OFFER_DECLINE_REASON_INSUFFICIENT_RESOURCES);
        }
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

    /**
     * Waits for the notification of this kind to a user: made by the real events, in a listener of its own, a
     * moment after what caused it (ADR 0026).
     */
    java.util.Map<String, Object> awaitNotification(UUID userId, String kind) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var rows = jdbc.queryForList(
                    "select kind, title, body, link, in_app, email_wanted, email_sent_at"
                            + " from notifications.notifications where user_id = ? and kind = ? order by id",
                    userId,
                    kind);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no " + kind + " notification for " + userId);
    }

    /**
     * Waits until the session holds no credit. A session that ran keeps its hold until metering closes its usage,
     * a moment after it ends (ADR 0017).
     */
    void awaitHoldReleased(UUID sessionId) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(
                            "select count(*) from billing.reservations where session_id = ? and status = 'active'",
                            Integer.class,
                            sessionId)
                    == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("session " + sessionId + " still holds credit");
    }

    /**
     * Ends what the test left behind, then disconnects, so nothing of this test is ever matched onto another
     * test's hosts. A session not yet running is cancelled by its renter. A running one fails, reported by
     * its agent as a real one would; if its host is gone, its lease is made to lapse, and once it's back in
     * REQUESTED it's cancelled.
     */
    @Override
    public void close() {
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!settle() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            hosts.forEach(ConnectedHost::disconnect);
            hosts.clear();
            owners.clear();
        }
    }

    /** One round of tidying; true once every session of this test has ended. */
    private boolean settle() {
        boolean settled = true;
        for (var entry : owners.entrySet()) {
            var sessionId = entry.getKey();
            var row = jdbc.queryForMap("select state, host_id, attempt from sessions.sessions where id = ?", sessionId);
            var state = (String) row.get("state");
            switch (state) {
                case "REQUESTED", "MATCHED", "PROVISIONING" -> {
                    settled = false;
                    try {
                        mvc.perform(post("/v1/sessions/" + sessionId + "/cancel")
                                .header("Authorization", entry.getValue().bearer()));
                    } catch (Exception e) {
                        // Tried again next round.
                    }
                }
                case "RUNNING" -> {
                    settled = false;
                    var host = hosts.stream()
                            .filter(candidate -> candidate.id().equals(row.get("host_id")))
                            .findFirst();
                    if (host.isEmpty() || !abandon(host.get(), sessionId, (Integer) row.get("attempt"))) {
                        jdbc.update(
                                "update sessions.sessions set lease_expires_at = now() - interval '1 hour' where id = ?",
                                sessionId);
                    }
                }
                case "HOST_LOST", "FINISHING" -> settled = false;
                default -> {
                    // Ended.
                }
            }
        }
        return settled;
    }

    /** The host's agent reports the attempt failed; false if its stream is gone. */
    private static boolean abandon(ConnectedHost host, UUID sessionId, int attempt) {
        var request = TestAgent.request()
                .setSessionStatus(SessionStatus.newBuilder()
                        .setSessionId(sessionId.toString())
                        .setAttempt(attempt)
                        .setState(SessionState.SESSION_STATE_FAILED)
                        .setError(SessionError.newBuilder().setCode("test_finished")))
                .build();
        try {
            host.stream().send(request);
            host.stream()
                    .await(
                            message -> message.hasAck()
                                    && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                            Duration.ofSeconds(1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (AssertionError | RuntimeException streamGone) {
            return false;
        }
    }
}
