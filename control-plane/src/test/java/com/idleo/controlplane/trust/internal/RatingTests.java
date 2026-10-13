package com.idleo.controlplane.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionNeverRan;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ratings (ADR 0028): only the renter rates, only an ended session that ran a minute, once, changed within 7 days;
 * a host sees its average from 5 ratings, over 90 days, and never who rated it or what they wrote.
 */
@ControlPlaneTest
class RatingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    private EndedSessions ended;
    private TestUsers.TestUser renter;

    @BeforeEach
    void setUp() throws Exception {
        ended = new EndedSessions(jdbc, clock);
        renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** A session of the renter's on the host that ran {@code ranFor} and ended {@code endedAgo} ago. */
    private UUID endedSession(UUID renterId, UUID hostId, Duration ranFor, Duration endedAgo) {
        var sessionId = UUID.randomUUID();
        var endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(endedAgo);
        ended.on(new SessionEnded(sessionId, renterId, hostId, "COMPLETED", endedAt.minus(ranFor), endedAt));
        return sessionId;
    }

    private UUID endedSession(UUID hostId) {
        return endedSession(renter.id(), hostId, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    private ResultActions rate(TestUsers.TestUser user, UUID sessionId, String body) throws Exception {
        return mvc.perform(post("/v1/sessions/" + sessionId + "/rating")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions standing(TestUsers.TestUser user, UUID sessionId) throws Exception {
        return mvc.perform(get("/v1/sessions/" + sessionId + "/rating").header("Authorization", user.bearer()));
    }

    @Test
    void theRenterRatesOnceAndChangesItWithinSevenDays() throws Exception {
        var sessionId = endedSession(UUID.randomUUID());
        standing(renter, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateable").value(true))
                .andExpect(jsonPath("$.stars").doesNotExist());

        rate(renter, sessionId, "{\"stars\":4}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateable").value(true))
                .andExpect(jsonPath("$.stars").value(4))
                .andExpect(jsonPath("$.comment").doesNotExist());
        var ratedAt = Instant.parse(JSON.readTree(
                        standing(renter, sessionId).andReturn().getResponse().getContentAsString())
                .get("ratedAt")
                .asString());

        // A second rating changes the first: one rating a session.
        clock.advance(Duration.ofDays(6));
        rate(renter, sessionId, "{\"stars\":2,\"comment\":\"  Slow to start.  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stars").value(2))
                .andExpect(jsonPath("$.comment").value("Slow to start."))
                .andExpect(jsonPath("$.ratedAt").value(ratedAt.toString()))
                .andExpect(jsonPath("$.rateableUntil")
                        .value(ratedAt.plus(Ratings.WINDOW).toString()));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from trust.ratings where session_id = ?", Integer.class, sessionId))
                .isOne();

        // Seven days after it was given, it's fixed.
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        rate(renter, sessionId, "{\"stars\":5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/rating-closed"));
        standing(renter, sessionId)
                .andExpect(jsonPath("$.rateable").value(false))
                .andExpect(jsonPath("$.stars").value(2));
    }

    @Test
    void onlyTheRenterOfAnEndedSessionCanRateIt() throws Exception {
        var sessionId = endedSession(UUID.randomUUID());
        var someoneElse = TestUsers.signUp(mvc);

        rate(someoneElse, sessionId, "{\"stars\":1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        standing(someoneElse, sessionId).andExpect(status().isNotFound());
        // A session trust hasn't seen end, running or not, is not-found too.
        rate(renter, UUID.randomUUID(), "{\"stars\":5}").andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from trust.ratings where session_id = ?", Integer.class, sessionId))
                .isZero();
    }

    @Test
    void aSessionThatDidntRunAMinuteCantBeRated() throws Exception {
        var shortOne = endedSession(renter.id(), UUID.randomUUID(), Duration.ofSeconds(59), Duration.ofMinutes(1));
        rate(renter, shortOne, "{\"stars\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/session-not-rateable"));
        standing(renter, shortOne)
                .andExpect(jsonPath("$.rateable").value(false))
                .andExpect(jsonPath("$.rateableUntil").doesNotExist());

        var exactlyAMinute =
                endedSession(renter.id(), UUID.randomUUID(), Duration.ofSeconds(60), Duration.ofMinutes(1));
        rate(renter, exactlyAMinute, "{\"stars\":3}").andExpect(status().isOk());

        var neverRan = UUID.randomUUID();
        ended.on(new SessionNeverRan(neverRan, renter.id(), "NO_HOST_FREE", clock.instant()));
        rate(renter, neverRan, "{\"stars\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/session-not-rateable"));
    }

    @Test
    void aSessionCanBeRatedOnlyWithinSevenDaysOfEnding() throws Exception {
        var lastWeek = endedSession(renter.id(), UUID.randomUUID(), Duration.ofMinutes(5), Duration.ofDays(7));
        rate(renter, lastWeek, "{\"stars\":4}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/rating-closed"));

        var sixDaysAgo = endedSession(renter.id(), UUID.randomUUID(), Duration.ofMinutes(5), Duration.ofDays(6));
        rate(renter, sixDaysAgo, "{\"stars\":4}").andExpect(status().isOk());
    }

    @Test
    void starsAreOneToFiveAndACommentAtMostFiveHundredCharacters() throws Exception {
        var sessionId = endedSession(UUID.randomUUID());
        for (var body : new String[] {"{\"stars\":0}", "{\"stars\":6}", "{}", "{\"stars\":\"many\"}"}) {
            rate(renter, sessionId, body).andExpect(status().isBadRequest());
        }
        rate(renter, sessionId, "{\"stars\":3,\"comment\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        rate(renter, sessionId, "{\"stars\":3,\"comment\":\"" + "x".repeat(500) + "\"}")
                .andExpect(status().isOk());
        // A blank comment is no comment.
        rate(renter, sessionId, "{\"stars\":3,\"comment\":\"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").doesNotExist());
    }

    @Test
    void aHostSeesItsAverageFromFiveRatingsOverNinetyDaysAndNeverWhoRatedIt() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var hostId = UUID.fromString(JSON.readTree(mvc.perform(post("/v1/hosts")
                                .header("Authorization", owner.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Rated laptop\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());

        // A rating of a session that ended 91 days ago doesn't count.
        var old = endedSession(renter.id(), hostId, Duration.ofMinutes(5), Duration.ofDays(1));
        rate(renter, old, "{\"stars\":1}").andExpect(status().isOk());
        jdbcTemplate.update(
                "update trust.ended_sessions set ended_at = ended_at - interval '90 days' where session_id = ?", old);

        int[] stars = {5, 5, 5, 4};
        for (int given : stars) {
            var rater = TestUsers.signUp(mvc);
            rate(
                            rater,
                            endedSession(rater.id(), hostId, Duration.ofMinutes(5), Duration.ofMinutes(1)),
                            "{\"stars\":" + given + ",\"comment\":\"secret words from " + rater.id() + "\"}")
                    .andExpect(status().isOk());
        }
        hostView(owner, hostId).andExpect(jsonPath("$.rating").doesNotExist());

        var fifth = TestUsers.signUp(mvc);
        rate(fifth, endedSession(fifth.id(), hostId, Duration.ofMinutes(5), Duration.ofMinutes(1)), "{\"stars\":4}")
                .andExpect(status().isOk());
        var body = hostView(owner, hostId)
                .andExpect(jsonPath("$.rating.averageStars").value(4.6))
                .andExpect(jsonPath("$.rating.count").value(5))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var list = mvc.perform(get("/v1/hosts").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.hosts[0].rating.count").value(5))
                .andReturn()
                .getResponse()
                .getContentAsString();
        for (var seen : new String[] {body, list}) {
            assertThat(seen)
                    .doesNotContain(
                            "secret words", renter.id().toString(), fifth.id().toString());
        }
    }

    private ResultActions hostView(TestUsers.TestUser owner, UUID hostId) throws Exception {
        return mvc.perform(get("/v1/hosts/" + hostId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void anEndedSessionIsRecordedFromTheSessionsModulesEvent() throws Exception {
        var sessionId = UUID.randomUUID();
        var hostId = UUID.randomUUID();
        var endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        transactions.executeWithoutResult(status -> events.publishEvent(new SessionEnded(
                sessionId, renter.id(), hostId, "COMPLETED", endedAt.minus(Duration.ofMinutes(3)), endedAt)));

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jdbcTemplate.queryForObject(
                                "select count(*) from trust.ended_sessions where session_id = ?",
                                Integer.class,
                                sessionId)
                        == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(jdbcTemplate.queryForMap(
                        "select renter_id, host_id, state from trust.ended_sessions where session_id = ?", sessionId))
                .containsEntry("renter_id", renter.id())
                .containsEntry("host_id", hostId)
                .containsEntry("state", "COMPLETED");
        // Recording it twice changes nothing.
        ended.on(new SessionEnded(sessionId, renter.id(), UUID.randomUUID(), "FAILED", endedAt, endedAt));
        assertThat(jdbcTemplate.queryForObject(
                        "select host_id from trust.ended_sessions where session_id = ?", UUID.class, sessionId))
                .isEqualTo(hostId);
    }
}
