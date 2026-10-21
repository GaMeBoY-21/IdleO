package com.idleo.controlplane.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.notifications.internal.RecordingEmailSender;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asking for an account to be deleted (ADR 0037): only after a fresh sign-in, in a browser, when nothing stops it;
 * confirmed by a single-use, hashed link that works for a day; then a wait nobody can skip from the API, cancelled
 * with one click, in the app or from the email.
 */
@ControlPlaneTest
class DeletionTests {

    private static final Pattern LINK = Pattern.compile("request=([0-9a-f-]{36})&token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingEmailSender mail;

    @Autowired
    Erasure erasure;

    @BeforeEach
    void clearMail() {
        mail.clear();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private People.Made person(String name) throws Exception {
        return People.make(
                mvc,
                jdbc,
                environment,
                clock,
                name + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private MockHttpServletRequestBuilder fresh(MockHttpServletRequestBuilder request, People.Made person) {
        return request.header("Authorization", person.signedInAt(clock.instant()));
    }

    /** The newest email to the person whose subject starts so, and its link's request and token. */
    private String[] link(People.Made person, String subject) {
        var email = mail.sent().reversed().stream()
                .filter(sent ->
                        sent.to().equals(person.email()) && sent.subject().startsWith(subject))
                .findFirst()
                .orElseThrow();
        var matcher = LINK.matcher(email.text());
        assertThat(matcher.find()).as(email.text()).isTrue();
        return new String[] {matcher.group(1), matcher.group(2)};
    }

    private void confirm(People.Made person, String[] link, int status) throws Exception {
        mvc.perform(fresh(post("/v1/me/deletion/confirm"), person)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"token\":\"%s\"}".formatted(link[0], link[1])))
                .andExpect(status().is(status));
    }

    @Test
    void needsASignInInTheLastTenMinutesInABrowser() throws Exception {
        var asha = person("asha");
        mvc.perform(post("/v1/me/deletion")
                        .header("Authorization", asha.signedInAt(clock.instant().minusSeconds(660))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/fresh-sign-in-required"));
        mvc.perform(get("/v1/me/deletion")
                        .header("Authorization", asha.signedInAt(clock.instant().minusSeconds(660))))
                .andExpect(jsonPath("$.freshSignIn").value(false));
        var key = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", asha.user().bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"ci\",\"scope\":\"full\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
        mvc.perform(post("/v1/me/deletion").header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
        assertThat(mail.sent()).noneMatch(sent -> sent.subject().startsWith("Confirm deleting"));

        mvc.perform(fresh(post("/v1/me/deletion"), asha))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.request.state").value("awaiting_confirmation"))
                .andExpect(jsonPath("$.losses[0].code").value("credit-forfeited"))
                .andExpect(jsonPath("$.losses[0].amountPaise").value(2500));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.security_events where user_id = ? and kind = 'deletion_requested'",
                        Integer.class,
                        asha.ids().user()))
                .isOne();
    }

    @Test
    void theEmailedLinkWorksOnceForADayAndIsKeptOnlyAsItsHash() throws Exception {
        var asha = person("asha");
        mvc.perform(fresh(post("/v1/me/deletion"), asha)).andExpect(status().isAccepted());
        var first = link(asha, "Confirm deleting");
        // Asked again: a new link, and the old one stops working.
        mvc.perform(fresh(post("/v1/me/deletion"), asha)).andExpect(status().isAccepted());
        var link = link(asha, "Confirm deleting");
        assertThat(link[1]).isNotEqualTo(first[1]);
        var rows = String.join(" ", jdbc.queryForList("select t::text from privacy.deletion_requests t", String.class));
        assertThat(rows).doesNotContain(link[1]).doesNotContain(first[1]);

        confirm(asha, first, 400);
        confirm(asha, new String[] {link[0], "not-the-token"}, 400);
        confirm(asha, link, 200);
        confirm(asha, link, 400);

        var scheduled = jdbc.queryForMap(
                "select state, delete_after - confirmed_at as wait from privacy.deletion_requests where id = ?::uuid",
                link[0]);
        assertThat(scheduled.get("state")).isEqualTo("scheduled");
        assertThat(scheduled.get("wait").toString()).contains("7 days");
        assertThat(mail.sent().reversed().getFirst().subject()).startsWith("Your account will be deleted on ");

        // A day and a link more: too late.
        var bina = person("bina");
        mvc.perform(fresh(post("/v1/me/deletion"), bina)).andExpect(status().isAccepted());
        var late = link(bina, "Confirm deleting");
        clock.advance(Duration.ofHours(25));
        confirm(bina, late, 400);
        mvc.perform(get("/v1/me/deletion").header("Authorization", bina.user().bearer()))
                .andExpect(jsonPath("$.request.state").value("expired"));
    }

    @Test
    void theWaitIsCancelledWithOneClickInTheAppOrFromTheEmailAndNothingShortensIt() throws Exception {
        var asha = person("asha");
        mvc.perform(fresh(post("/v1/me/deletion"), asha)).andExpect(status().isAccepted());
        confirm(asha, link(asha, "Confirm deleting"), 200);
        var cancel = link(asha, "Your account will be deleted on");

        // Due nowhere near: the job leaves it, and asking again from the API doesn't speed it up.
        assertThat(erasure.due()).doesNotContain(UUID.fromString(cancel[0]));
        mvc.perform(fresh(post("/v1/me/deletion"), asha))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/deletion-already-requested"));

        // From the email, signed out: one click.
        mvc.perform(post("/v1/deletion-requests/" + cancel[0] + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/deletion-link-invalid"));
        mvc.perform(post("/v1/deletion-requests/" + cancel[0] + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + cancel[1] + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"));
        clock.advance(Duration.ofDays(8));
        assertThat(erasure.due()).doesNotContain(UUID.fromString(cancel[0]));
        clock.reset();

        // In the app.
        mvc.perform(fresh(post("/v1/me/deletion"), asha)).andExpect(status().isAccepted());
        confirm(asha, link(asha, "Confirm deleting"), 200);
        mvc.perform(delete("/v1/me/deletion")
                        .header("Authorization", asha.user().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("cancelled"));
        mvc.perform(delete("/v1/me/deletion")
                        .header("Authorization", asha.user().bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/no-deletion-pending"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.security_events where user_id = ? and kind = 'deletion_cancelled'",
                        Integer.class,
                        asha.ids().user()))
                .isEqualTo(2);
    }

    @Test
    void remindsADayBeforeWithACancelLinkOfItsOwn() throws Exception {
        var asha = person("asha");
        mvc.perform(fresh(post("/v1/me/deletion"), asha)).andExpect(status().isAccepted());
        confirm(asha, link(asha, "Confirm deleting"), 200);
        var first = link(asha, "Your account will be deleted on");
        clock.advance(Duration.ofDays(6).plusHours(1));
        erasure.sweep();
        var reminder = link(asha, "Your account will be deleted tomorrow");
        erasure.sweep();
        assertThat(mail.sent().stream().filter(sent -> sent.subject().endsWith("tomorrow")))
                .hasSize(1);
        assertThat(reminder[1]).isNotEqualTo(first[1]);
        // Either link cancels.
        mvc.perform(post("/v1/deletion-requests/" + first[0] + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first[1] + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void whatStopsItIsSaidPlainly() throws Exception {
        var asha = person("asha");
        var session = asha.ids().sessions().getFirst();
        jdbc.update("update sessions.sessions set state = 'RUNNING' where id = ?", session);
        var reported = UUID.randomUUID();
        jdbc.update(
                "insert into trust.ended_sessions (session_id, renter_id, state, ended_at, recorded_at)"
                        + " values (?, ?, 'FAILED', now(), now())",
                reported,
                asha.ids().user());
        jdbc.update(
                "insert into trust.disputes (id, session_id, renter_id, reason, state, created_at)"
                        + " values (?, ?, ?, 'failed', 'open', now())",
                UUID.randomUUID(),
                reported,
                asha.ids().user());
        var host = UUID.randomUUID();
        jdbc.update(
                "insert into hosts.hosts (id, owner_user_id, name, status, created_at, updated_at, version)"
                        + " values (?, ?, 'busy laptop', 'active', now(), now(), 0)",
                host,
                asha.ids().user());
        jdbc.update("update sessions.sessions set host_id = ? where id = ?", host, session);

        mvc.perform(fresh(post("/v1/me/deletion"), asha))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/deletion-blocked"))
                .andExpect(jsonPath("$.blockers[*].code")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "session-running", "open-report", "host-busy")));
        mvc.perform(get("/v1/me/deletion").header("Authorization", asha.user().bearer()))
                .andExpect(jsonPath("$.blockers.length()").value(3))
                .andExpect(jsonPath("$.blockers[?(@.code == 'host-busy')].message")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("busy laptop"))));
        assertThat(mail.sent()).noneMatch(sent -> sent.subject().startsWith("Confirm deleting"));
    }

    @Test
    void onlyALegalOrderShortensTheWaitAndItsAudited() throws Exception {
        var asha = person("asha");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(post("/admin/v1/users/" + asha.ids().user() + "/deletion/legal-order"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"court order 2026/114\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("scheduled"))
                .andExpect(jsonPath("$.request.legalOrder").value(true));
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'account.erase_legal_order'"
                                + " and target_id = ?",
                        String.class,
                        asha.ids().user().toString()))
                .isEqualTo("court order 2026/114");
        var request = jdbc.queryForObject(
                "select id from privacy.deletion_requests where user_id = ?",
                UUID.class,
                asha.ids().user());
        assertThat(erasure.due()).contains(request);
        // The person can't cancel what a legal order scheduled from the app? They can: it's still their account until
        // the job runs. The order stands in the audit log either way.
        mvc.perform(get("/admin/v1/users/" + asha.ids().user() + "/deletion")
                        .header("Authorization", asha.user().bearer()))
                .andExpect(status().isNotFound());
    }
}
