package com.idleo.controlplane.support.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestHosts;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Broadcast notices (ADR 0056): scheduled by an approved request, sent at their time to the owners whose agent runs a
 * version in a range, in the app and by email only when asked, once, and logged with how many they reached; cancelled
 * before they go.
 */
@ControlPlaneTest
class BroadcastTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    Broadcasts broadcasts;

    @LocalGrpcServerPort
    int grpcPort;

    private MockHttpServletResponse ask(TestAdmins.TestAdmin admin, String body) throws Exception {
        return mvc.perform(admin.as(post("/admin/v1/broadcasts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private UUID scheduled(String subject) {
        return jdbc.queryForObject("select id from support.broadcasts where subject = ?", UUID.class, subject);
    }

    @Test
    void anApprovedNoticeToAnAgentVersionRangeReachesThoseOwnersInTheAppOnceAtItsTime() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        host.disconnect();
        var version = "6.6." + (System.nanoTime() % 1000);
        jdbc.update("update hosts.hosts set agent_version = ? where id = ?", version, host.id());
        var owner = jdbc.queryForObject("select owner_user_id from hosts.hosts where id = ?", UUID.class, host.id());
        var subject = "Update your app " + UUID.randomUUID();
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var at = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);

        var asked = ask(admin, """
                {"subject":"%s","body":"Version %s has a bug; the next one fixes it.","audience":"AGENT_VERSIONS",
                 "minAgentVersion":"%s","maxAgentVersion":"%s","email":false,"sendAt":"%s",
                 "reason":"an agent bug in that release"}
                """.formatted(subject, version, version, version, at));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        assertThat(jdbc.queryForObject(
                        "select count(*) from support.broadcasts where subject = ?", Integer.class, subject))
                .as("nothing until approved")
                .isZero();
        TestAdmins.approve(mvc, jdbc, clock, asked.getContentAsString());
        var id = scheduled(subject);

        assertThat(broadcasts.send(id)).as("claimed once").isTrue();
        assertThat(broadcasts.send(id)).isFalse();
        Awaitility.await()
                .until(() -> jdbc.queryForObject(
                                "select count(*) from notifications.notifications where user_id = ? and title = ?",
                                Integer.class,
                                owner,
                                subject)
                        == 1);
        assertThat(jdbc.queryForObject(
                        "select email_wanted from notifications.notifications where user_id = ? and title = ?",
                        Boolean.class,
                        owner,
                        subject))
                .as("in the app only, as asked")
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select state || ':' || reached from support.broadcasts where id = ?", String.class, id))
                .isEqualTo("sent:1");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where target_type = 'broadcast' and target_id = ?"
                                + " and action in ('broadcast.schedule', 'broadcast.send')",
                        Integer.class,
                        id.toString()))
                .isEqualTo(2);

        var list = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/broadcasts")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(list.get("items").toString()).contains(subject);
    }

    @Test
    void aScheduledNoticeCanBeCancelledBeforeItGoes() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var subject = "Maintenance tonight " + UUID.randomUUID();
        var asked = ask(admin, """
                {"subject":"%s","body":"We'll be read-only for an hour.","audience":"HOSTS","email":true,
                 "sendAt":"%s","reason":"tonight's database upgrade"}
                """.formatted(
                        subject, Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS)));
        TestAdmins.approve(mvc, jdbc, clock, asked.getContentAsString());
        var id = scheduled(subject);
        var cancelled = mvc.perform(admin.as(post("/admin/v1/broadcasts/" + id + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the upgrade moved to Sunday\"}"))
                .andReturn()
                .getResponse();
        assertThat(cancelled.getStatus()).as(cancelled.getContentAsString()).isEqualTo(200);
        assertThat(broadcasts.send(id)).isFalse();
        assertThat(jdbc.queryForObject("select state from support.broadcasts where id = ?", String.class, id))
                .isEqualTo("cancelled");
    }

    @Test
    void aTagNoticeNamesItsTagAndARangeAVersionBeforeItsAskedFor() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        assertThat(ask(admin, """
                        {"subject":"Hi","body":"Hello.","audience":"TAG","email":false,"reason":"a test of the rules"}
                        """).getStatus()).isEqualTo(400);
        assertThat(ask(admin, """
                        {"subject":"Hi","body":"Hello.","audience":"AGENT_VERSIONS","minAgentVersion":"soon",
                         "email":false,"reason":"a test of the rules"}
                        """).getStatus()).isEqualTo(400);
    }
}
