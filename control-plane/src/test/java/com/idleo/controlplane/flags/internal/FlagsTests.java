package com.idleo.controlplane.flags.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Flags;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestFlags;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console's flags (ADR 0056): pausing is immediate, audited, and tells the Owners when the flag says so; running one
 * again waits for another admin, or with no one else runs at once; a pause until a time runs again by itself then; and
 * the public status says what's paused but never why an admin paused it.
 */
@ControlPlaneTest
class FlagsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    Flags flags;

    @Autowired
    FlagStore store;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void runEverythingAgain() {
        clock.reset();
        TestFlags.runAll(context);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    private JsonNode flag(TestAdmins.TestAdmin admin, String key) throws Exception {
        var list = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/flags")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        for (var item : list.get("items")) {
            if (item.get("id").asString().equals(key)) {
                return item;
            }
        }
        throw new AssertionError("no flag " + key);
    }

    @Test
    void theConsoleListsEveryFlagWithItsOwnerAndSafeValue() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var list = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/flags")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(list.get("items")).hasSize(Flag.values().length);
        var payouts = flag(admin, "payouts.send");
        assertThat(payouts.get("owner").asString()).isEqualTo("Finance");
        assertThat(payouts.get("pausedIfUnknown").asBoolean()).isTrue();
        assertThat(payouts.get("emailsOwners").asBoolean()).isTrue();
        assertThat(flag(admin, "bookings.new").get("pausedIfUnknown").asBoolean())
                .isFalse();
    }

    @Test
    void pausingIsImmediateAuditedAndTellsTheOwners() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var paused =
                send(admin.as(post("/admin/v1/flags/bookings.new/pause")), "{\"reason\":\"a bad matcher release\"}");
        assertThat(paused.getStatus()).as(paused.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(paused.getContentAsString()).get("paused").asBoolean())
                .isTrue();
        assertThat(flags.paused(Flag.BOOKINGS_NEW))
                .as("this instance knows at once")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'flag.pause' and target_id = 'bookings.new'"
                                + " and actor_user_id = ? and reason = 'a bad matcher release'",
                        Integer.class,
                        admin.id()))
                .isEqualTo(1);
        Awaitility.await()
                .until(() -> jdbc.queryForObject(
                                "select count(*) from notifications.notifications where user_id = ?"
                                        + " and title = 'bookings.new was paused'",
                                Integer.class,
                                admin.id())
                        == 1);
    }

    @Test
    void pausingNeedsARecentFactor() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        clock.advance(Duration.ofMinutes(6));
        var refused = send(admin.as(post("/admin/v1/flags/topups/pause")), "{\"reason\":\"fraud wave on top-ups\"}");
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("step-up-required");
        assertThat(flags.paused(Flag.TOPUPS)).isFalse();
    }

    @Test
    void runningAgainWaitsForAnotherAdminsApproval() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        send(admin.as(post("/admin/v1/flags/topups/pause")), "{\"reason\":\"fraud wave on top-ups\"}");
        var asked = send(admin.as(post("/admin/v1/flags/topups/resume")), "{\"reason\":\"the wave has passed\"}");
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        var request = JSON.readTree(asked.getContentAsString());
        assertThat(request.get("state").asString()).isEqualTo("pending");
        assertThat(request.get("solo").asBoolean()).isFalse();
        assertThat(flags.paused(Flag.TOPUPS)).as("still paused until approved").isTrue();

        TestAdmins.approve(mvc, jdbc, clock, asked.getContentAsString());
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !flags.paused(Flag.TOPUPS));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'flag.resume' and target_id = 'topups'"
                                + " and actor_user_id = ?",
                        Integer.class,
                        admin.id()))
                .as("run as the requester asked it")
                .isEqualTo(1);
    }

    @Test
    void withNoOneElseToApproveRunningAgainDoesntWaitOutACoolingOff() throws Exception {
        jdbc.update("update identity.admins set locked_at = now() where locked_at is null");
        jdbc.update("update identity.approval_requests set state = 'cancelled' where state = 'pending'");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        send(admin.as(post("/admin/v1/flags/signups/pause")), "{\"reason\":\"a bot wave of sign-ups\"}");

        var asked = send(admin.as(post("/admin/v1/flags/signups/resume")), "{\"reason\":\"the bots have stopped\"}");
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        var request = JSON.readTree(asked.getContentAsString());
        assertThat(request.get("solo").asBoolean()).isTrue();
        assertThat(request.get("state").asString()).isEqualTo("done");
        assertThat(flags.paused(Flag.SIGNUPS))
                .as("running again now, not in an hour")
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'approval.self_approve'"
                                + " and target_id = ?",
                        Integer.class,
                        request.get("id").asString()))
                .isEqualTo(1);
    }

    @Test
    void aPauseForTwoHoursRunsAgainByItself() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var until = clock.instant().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        var paused = send(
                admin.as(post("/admin/v1/flags/hosts.link/pause")),
                "{\"reason\":\"a broken enrolment build\",\"until\":\"" + until + "\",\"resumeAutomatically\":true}");
        assertThat(paused.getStatus()).as(paused.getContentAsString()).isEqualTo(200);
        assertThat(flags.pause(Flag.HOSTS_LINK))
                .hasValueSatisfying(pause -> assertThat(pause.until()).isEqualTo(until));

        clock.advance(Duration.ofHours(2).plusSeconds(1));
        assertThat(flags.paused(Flag.HOSTS_LINK))
                .as("over at its time, on every instance")
                .isFalse();
        store.expire();
        assertThat(jdbc.queryForObject("select paused from flags.flags where key = 'hosts.link'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'flag.resume' and target_id = 'hosts.link'"
                                + " and actor_kind = 'system'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anUnknownFlagIsNotFoundAndAPastEndIsRefused() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        assertThat(send(admin.as(post("/admin/v1/flags/everything/pause")), "{\"reason\":\"a typo in the console\"}")
                        .getStatus())
                .isEqualTo(404);
        var past = Instant.now().minusSeconds(60);
        var refused = send(
                admin.as(post("/admin/v1/flags/topups/pause")),
                "{\"reason\":\"fraud wave on top-ups\",\"until\":\"" + past + "\"}");
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(flags.paused(Flag.TOPUPS)).isFalse();
    }

    @Test
    void thePublicStatusSaysWhatsPausedButNeverWhy() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var until = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        send(
                admin.as(post("/admin/v1/flags/maintenance/pause")),
                "{\"reason\":\"database upgrade ticket 42\",\"until\":\"" + until + "\"}");
        var response = mvc.perform(get("/v1/public/status")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("max-age=10");
        var status = JSON.readTree(response.getContentAsString());
        assertThat(status.get("maintenance").asBoolean()).isTrue();
        assertThat(status.get("paused").get(0).get("flag").asString()).isEqualTo("maintenance");
        assertThat(status.get("paused").get(0).get("message").asString())
                .startsWith("IdleO is down for maintenance: we expect to be back by");
        assertThat(response.getContentAsString()).doesNotContain("ticket 42");
    }
}
