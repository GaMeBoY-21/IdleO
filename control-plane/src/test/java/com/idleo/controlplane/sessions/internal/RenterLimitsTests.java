package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.internal.TestCredit;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The renter limits (technical design "Limits", ADR 0025), set low: 5 requests a minute and 2 CLI sign-ins an hour
 * from one address; 3 new sessions an hour, 2 for an account less than a day old, and 2 at once.
 */
@ControlPlaneTest
@TestPropertySource(
        properties = {
            "limits.requests-per-minute=5",
            "limits.cli-logins-per-hour=2",
            "sessions.limits.new-per-hour=3",
            "sessions.limits.new-per-hour-first-day=2",
            "sessions.limits.at-once=2"
        })
class RenterLimitsTests {

    private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    SessionStore store;

    @Autowired
    PlatformTransactionManager transactions;

    private final JsonMapper json = JsonMapper.builder().build();
    /** Each test calls from its own address, so the per-address counters never meet. */
    private String address;

    @BeforeEach
    void startOfAMinute() {
        // Fixed one-minute windows: start each test just after one begins, so it never straddles two.
        clock.advance(Duration.ofMillis(60_000 - clock.millis() % 60_000 + 500));
        address = "10.0.0." + ADDRESSES.getAndIncrement();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    private TestUser renter(boolean dayOld) throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(3));
        if (dayOld) {
            jdbc.update(
                    "update identity.users set created_at = created_at - interval '2 days' where id = ?", renter.id());
        }
        TestCredit.grant(context, renter.id(), 50_000);
        return renter;
    }

    private ResultActions book(TestUser renter, String key) throws Exception {
        return mvc.perform(post("/v1/sessions")
                .header("Authorization", renter.bearer())
                .header("Idempotency-Key", "\"" + key + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 1000}"));
    }

    /** Books a session and waits for it to end (no host is free here), so only the hourly limit is in play. */
    private void bookAndEnd(TestUser renter, String key) throws Exception {
        var id = json.readTree(book(renter, key)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!jdbc.queryForObject("select state from sessions.sessions where id = ?::uuid", String.class, id)
                .equals("NO_HOST_FREE")) {
            assertThat(System.nanoTime()).as("session %s ended", id).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void sixRequestsInAMinuteIsOneTooMany() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(1)); // its first request
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/v1/me").header("Authorization", renter.bearer())).andExpect(status().isOk());
        }

        var refused = mvc.perform(get("/v1/me").header("Authorization", renter.bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/rate-limited"))
                .andReturn()
                .getResponse();
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 60);

        // Someone else isn't held back, and the renter may go on in the next minute.
        var other = TestUsers.signUpLasting(mvc, Duration.ofHours(1));
        mvc.perform(get("/v1/me").header("Authorization", other.bearer())).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(61));
        mvc.perform(get("/v1/me").header("Authorization", renter.bearer())).andExpect(status().isOk());
    }

    @Test
    void readingOnInALogCountsOnce() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(1));
        var session = UUID.randomUUID();
        // The reads that follow a log, with a cursor, are free; whatever they answer, they're never a 429.
        for (int i = 0; i < 12; i++) {
            var status = mvc.perform(get("/v1/sessions/" + session + "/logs")
                            .param("cursor", "1-" + i)
                            .header("Authorization", renter.bearer()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).isNotEqualTo(429);
        }
        // The read that opens it counts, like any other request.
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/v1/sessions/" + session + "/logs").header("Authorization", renter.bearer()));
        }
        mvc.perform(get("/v1/sessions/" + session + "/logs").header("Authorization", renter.bearer()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void anAddressStartsAtMostTwoCliSignInsAnHour() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/v1/cli-logins"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceName\":\"box\"}"))
                    .andExpect(status().isCreated());
        }
        var refused = mvc.perform(from(post("/v1/cli-logins"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"box\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/rate-limited"))
                .andExpect(header().exists("Retry-After"))
                .andReturn()
                .getResponse();
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 3600);
    }

    @Test
    void callsWithoutASignInAreCountedByAddress() throws Exception {
        var login = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(post("/v1/cli-logins/" + login + "/token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pollToken\":\"x\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(from(post("/v1/cli-logins/" + login + "/token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pollToken\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aNewAccountBooksTwoSessionsAnHour() throws Exception {
        var renter = renter(false);
        bookAndEnd(renter, "first");
        bookAndEnd(renter, "second");

        var refused = book(renter, "third")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-new-sessions"))
                .andReturn()
                .getResponse();
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(3500, 3600);
        // A booking repeated with its key isn't a new one, and isn't refused.
        book(renter, "first").andExpect(status().isOk());

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        book(renter, "third").andExpect(status().isCreated());
    }

    @Test
    void anOlderAccountBooksThreeSessionsAnHour() throws Exception {
        var renter = renter(true);
        bookAndEnd(renter, "one");
        bookAndEnd(renter, "two");
        bookAndEnd(renter, "three");

        book(renter, "four")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-new-sessions"));
    }

    @Test
    void twoSessionsThatHaventEndedAreTheMostAtOnce() throws Exception {
        var renter = renter(true);
        var first = SessionTestData.requested(store, transactions, renter.id(), 5, 1000);
        SessionTestData.requested(store, transactions, renter.id(), 5, 1000);

        book(renter, "a-third")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/too-many-sessions"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.sessions where renter_id = ?", Integer.class, renter.id()))
                .isEqualTo(2);

        // Once one has ended, there's room again.
        jdbc.update("update sessions.sessions set state = 'CANCELLED' where id = ?", first);
        book(renter, "a-third").andExpect(status().isCreated());
    }
}
