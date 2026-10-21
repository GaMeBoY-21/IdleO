package com.idleo.controlplane.support.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.notifications.internal.RecordingEmailSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Support (ADR 0037): the Contact form, signed in or not, refusing what a machine sends; every ticket acknowledged by
 * email with its number; a person sees and answers only their own; admins answer by email with the number in the
 * subject, each action audited; privacy requests and grievances due by a date, overdue first and counted for the alert.
 */
@ControlPlaneTest
class SupportTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingEmailSender mail;

    @Autowired
    Environment environment;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private static String form(String topic, String replyTo, long elapsedMs, String extra) {
        return """
                {"topic":"%s","message":"Hello, my credit looks wrong.","replyTo":"%s","elapsedMs":%d%s}
                """.formatted(topic, replyTo, elapsedMs, extra);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        var response = mvc.perform(
                        request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(response.isEmpty() ? "{}" : response);
    }

    @Test
    void theContactFormTakesAMessageSignedOutAndAcknowledgesItWithItsNumber() throws Exception {
        var address = "visitor-" + UUID.randomUUID() + "@example.com";
        var ticket = send(post("/v1/support/tickets"), form("money", address, 9000, ""), 202);
        var number = ticket.get("number").asLong();
        assertThat(number).isGreaterThan(1000);
        assertThat(ticket.get("dueAt").isNull()).isTrue();

        var ack = mail.sent().getLast();
        assertThat(ack.to()).isEqualTo(address);
        assertThat(ack.subject()).isEqualTo("We've got your message (#" + number + ")");
        assertThat(ack.text()).contains("Reply here, not by email");

        // A machine: a field people never see, or no time to write it.
        send(post("/v1/support/tickets"), form("money", address, 9000, ",\"website\":\"http://spam\""), 422);
        send(post("/v1/support/tickets"), form("money", address, 800, ""), 422);
        // Signed out, no session or machine can be named; an unknown topic is refused.
        send(
                post("/v1/support/tickets"),
                form("session", address, 9000, ",\"sessionId\":\"" + UUID.randomUUID() + "\""),
                400);
        send(post("/v1/support/tickets"), form("weather", address, 9000, ""), 400);
        send(post("/v1/support/tickets"), form("money", "not an address", 9000, ""), 400);
    }

    @Test
    void aPersonSeesAndAnswersOnlyTheirOwn() throws Exception {
        var asha = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "asha-" + UUID.randomUUID().toString().substring(0, 8));
        var bina = TestUsers.signUp(mvc);
        var mine = send(
                post("/v1/support/tickets").header("Authorization", asha.user().bearer()),
                form(
                        "session",
                        asha.email(),
                        5000,
                        ",\"sessionId\":\"" + asha.ids().sessions().getFirst() + "\""),
                202);
        // Someone else's session can't be named.
        send(
                post("/v1/support/tickets").header("Authorization", bina.bearer()),
                form(
                        "session",
                        "bina@example.com",
                        5000,
                        ",\"sessionId\":\"" + asha.ids().sessions().getFirst() + "\""),
                400);

        mvc.perform(get("/v1/support/tickets")
                        .header("Authorization", asha.user().bearer()))
                .andExpect(jsonPath("$.tickets.length()").value(2));
        mvc.perform(get("/v1/support/tickets").header("Authorization", bina.bearer()))
                .andExpect(jsonPath("$.tickets.length()").value(0));
        mvc.perform(get("/v1/support/tickets/" + mine.get("id").asString()).header("Authorization", bina.bearer()))
                .andExpect(status().isNotFound());
        send(
                post("/v1/support/tickets/" + mine.get("id").asString() + "/messages")
                        .header("Authorization", bina.bearer()),
                "{\"message\":\"me too\"}",
                404);
        var thread = send(
                post("/v1/support/tickets/" + mine.get("id").asString() + "/messages")
                        .header("Authorization", asha.user().bearer()),
                "{\"message\":\"It happened again.\"}",
                200);
        assertThat(thread.get("messages")).hasSize(2);
        assertThat(thread.get("ticket").get("status").asString()).isEqualTo("open");
    }

    @Test
    void adminsAnswerByEmailOverdueFirstAndEveryActionIsAudited() throws Exception {
        var asha = TestUsers.signUpWithEmail(mvc, "asha-" + UUID.randomUUID() + "@example.com");
        var privacy = send(
                post("/v1/support/tickets").header("Authorization", asha.bearer()),
                form("privacy", "asha-requests@example.com", 5000, ""),
                202);
        var grievance = send(post("/v1/support/tickets"), form("grievance", "griever@example.com", 5000, ""), 202);
        assertThat(privacy.get("dueAt").asString()).isNotEmpty();
        assertThat(grievance.get("dueAt").asString()).isNotEmpty();

        // Both past their due date, as a month on would leave them.
        jdbc.update(
                "update support.tickets set due_at = ? where id in (?::uuid, ?::uuid)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(1))),
                privacy.get("id").asString(),
                grievance.get("id").asString());
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var list = send(admin.as(get("/admin/v1/tickets").param("status", "open")), "", 200)
                .get("tickets");
        assertThat(list.get(0).get("overdue").asBoolean()).isTrue();
        assertThat(meters.get("support.tickets.overdue").gauge().value()).isGreaterThanOrEqualTo(2);
        assertThat(meters.get("support.tickets.due_soon").gauge().value()).isGreaterThanOrEqualTo(2);

        var id = privacy.get("id").asString();
        var answered = send(
                admin.as(post("/admin/v1/tickets/" + id + "/answer")),
                "{\"message\":\"Your file is on its way.\",\"status\":\"resolved\"}",
                200);
        assertThat(answered.get("ticket").get("status").asString()).isEqualTo("resolved");
        var email = mail.sent().getLast();
        assertThat(email.to()).isEqualTo("asha-requests@example.com");
        assertThat(email.subject())
                .isEqualTo("Re: your message (#" + privacy.get("number").asLong() + ")");
        assertThat(email.text()).contains("Your file is on its way.").contains("Reply here, not by email");

        send(
                admin.as(post("/admin/v1/tickets/" + id + "/status")),
                "{\"status\":\"open\",\"reason\":\"the person wrote again by phone\"}",
                200);
        assertThat(jdbc.queryForList(
                        "select action from identity.audit_log where target_type = 'ticket' and target_id = ?"
                                + " order by created_at",
                        String.class,
                        id))
                .containsExactly("ticket.answer", "ticket.status");
        // A person can't reach the admin's view.
        mvc.perform(get("/admin/v1/tickets").header("Authorization", asha.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theContactPageNamesTheGrievanceOfficerFromConfiguration() throws Exception {
        mvc.perform(get("/v1/public/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grievanceOfficer.email").value("grievance@example.invalid"))
                .andExpect(jsonPath("$.responseDays").value(30))
                .andExpect(jsonPath("$.topics.length()").value(7));
    }

    @Test
    void aBreachNoticeIsEmailedToThoseChosenAndAuditedByIdOnly() throws Exception {
        var one = TestUsers.signUpWithEmail(mvc, "notice-one-" + UUID.randomUUID() + "@example.com");
        var two = TestUsers.signUpWithEmail(mvc, "notice-two-" + UUID.randomUUID() + "@example.com");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var sent = send(admin.as(post("/admin/v1/notices")), """
                {"userIds":["%s","%s"],"subject":"About your account","body":"Here is what happened.",
                 "reason":"incident 2026-10 notice"}
                """.formatted(one.id(), two.id()), 200);
        assertThat(sent.get("sent").asInt()).isEqualTo(2);
        assertThat(mail.sent()).extracting(RecordingEmailSender.Sent::subject).containsOnly("About your account");
        var row = jdbc.queryForMap(
                "select reason, after::text as after from identity.audit_log where action = 'notice.send'"
                        + " and target_id = ?",
                sent.get("noticeId").asString());
        assertThat(row.get("reason")).isEqualTo("incident 2026-10 notice");
        assertThat((String) row.get("after")).contains(one.id().toString()).doesNotContain("@");
    }
}
