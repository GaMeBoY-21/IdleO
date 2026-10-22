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
import java.net.URI;
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
            "limits.log-reads-per-minute=8",
            "limits.cli-logins-per-hour=2",
            "limits.host-links-per-hour=2",
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
        clock.advance(
                Duration.ofSeconds(61)); // signing up took two requests (reading the account, accepting the Terms)
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
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(1)); // two requests: the account, the Terms
        clock.advance(Duration.ofSeconds(61));
        for (int i = 0; i < 5; i++) {
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
    void everyLogReadCountsAgainstLogReadsOwnLimit() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(1)); // its first request, of the 5 for the rest
        var session = UUID.randomUUID();
        // With a cursor or without, and however the path is spelt, each read counts: 8 a minute, then a 429.
        for (int i = 0; i < 8; i++) {
            var request = i % 2 == 0
                    ? get("/v1/sessions/" + session + "/logs").param("cursor", "1-" + i)
                    : get(URI.create("/v1/sessions/" + session + "/l%6Fgs"));
            var status = mvc.perform(request.header("Authorization", renter.bearer()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as("read %d", i + 1).isNotEqualTo(429);
        }
        mvc.perform(get("/v1/sessions/" + session + "/logs")
                        .param("cursor", "1-9")
                        .header("Authorization", renter.bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/rate-limited"))
                .andExpect(header().exists("Retry-After"));
        // Following a log never used up the renter's other requests.
        mvc.perform(get("/v1/me").header("Authorization", renter.bearer())).andExpect(status().isOk());
    }

    @Test
    void anEventStreamOverTheLimitIsAProblemTheClientCanRead() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofHours(1));
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/v1/me").header("Authorization", renter.bearer()));
        }
        var session = SessionTestData.requested(store, transactions, renter.id(), 5, 1000);

        mvc.perform(get("/v1/sessions/" + session + "/events")
                        .header("Authorization", renter.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("/problems/rate-limited"));
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
    void anAddressAsksToLinkAtMostTwoHostsAnHour() throws Exception {
        var machine = "{\"machineName\":\"box\",\"os\":\"linux\",\"agentVersion\":\"0.1.0\",\"gpus\":[]}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/v1/host-links"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(machine))
                    .andExpect(status().isCreated());
        }
        mvc.perform(from(post("/v1/host-links"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machine))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/rate-limited"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void aCliSignInSpeltAnotherWayStillCountsAgainstTheHourlyLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post(URI.create(i == 0 ? "/v1/cli-logins" : "/v1/cli-l%6Fgins")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceName\":\"box\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(from(post(URI.create("/v1/cli%2Dlogins")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"box\"}"))
                .andExpect(status().isTooManyRequests());
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
