package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Notes, tags and linked accounts (ADR 0052). */
@ControlPlaneTest
class ConsoleNotesTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<PersonalData> modules;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return mvc.perform(admin.as(request)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private JsonNode note(TestAdmins.TestAdmin admin, UUID person, String body, boolean openInvestigation)
            throws Exception {
        var answer = send(
                admin,
                post("/admin/v1/notes"),
                Map.of(
                        "subject",
                        "user",
                        "id",
                        person,
                        "body",
                        body,
                        "pinned",
                        false,
                        "openInvestigation",
                        openInvestigation));
        assertThat(answer.getStatus()).isEqualTo(200);
        return json.readTree(answer.getContentAsString());
    }

    @Test
    void aNoteIsAuditedMaskedOnTheTimelineAndFixedAfterTenMinutes() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var written = note(admin, person, "Called from known.person@example.com, **twice**", false);
        assertThat(written.get("editable").asBoolean()).isTrue();
        var id = written.get("id").asString();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'note.add' and target_id = ?",
                        Integer.class,
                        person.toString()))
                .isEqualTo(1);

        var read = mvc.perform(
                        admin.as(get("/admin/v1/notes").param("subject", "user").param("id", person.toString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(read).doesNotContain("known.person@example.com").contains("**twice**");
        var timeline = mvc.perform(admin.as(get("/admin/v1/timeline")
                        .param("subject", "user")
                        .param("id", person.toString())
                        .param("kinds", "note")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(timeline).contains("\"note\"").doesNotContain("known.person@example.com");

        // Someone else can't change it; its author can, for 10 minutes.
        assertThat(send(other, post("/admin/v1/notes/" + id + "/edit"), Map.of("body", "No"))
                        .getStatus())
                .isEqualTo(409);
        var edited = send(admin, post("/admin/v1/notes/" + id + "/edit"), Map.of("body", "Fixed"));
        assertThat(edited.getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(11));
        assertThat(send(admin, post("/admin/v1/notes/" + id + "/edit"), Map.of("body", "Late"))
                        .getStatus())
                .isEqualTo(409);
    }

    @Test
    void aNoteOnNothingIsNotFound() throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(send(
                                admin,
                                post("/admin/v1/notes"),
                                Map.of(
                                        "subject",
                                        "host",
                                        "id",
                                        UUID.randomUUID(),
                                        "body",
                                        "Nobody here",
                                        "pinned",
                                        false,
                                        "openInvestigation",
                                        false))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void theExportHasNotesExceptThoseOfAnOpenInvestigationAndErasingDeletesThem() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        note(admin, person, "An ordinary note", false);
        var secret = note(admin, person, "Watching this card ring", true);
        var console = modules.stream()
                .filter(module -> module.module().equals("console"))
                .findFirst()
                .orElseThrow();
        assertThat(console.export(person).toString())
                .contains("An ordinary note")
                .doesNotContain("Watching this card ring");

        var closed = send(
                admin,
                post("/admin/v1/notes/" + secret.get("id").asString() + "/change"),
                Map.of("openInvestigation", false));
        assertThat(closed.getStatus()).isEqualTo(200);
        assertThat(console.export(person).toString()).contains("Watching this card ring");

        console.erase(person, PersonalData.ErasureStep.DELETE_RECORDS);
        assertThat(jdbc.queryForObject(
                        "select count(*) from console.notes where subject_user_id = ?", Integer.class, person))
                .isZero();
    }

    @Test
    void onlyTheOwnerManagesTheVocabularyAndTagsAreAudited() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        assertThat(send(support, post("/admin/v1/tags"), Map.of("id", "whale", "label", "Whale", "colour", "plain"))
                        .getStatus())
                .isEqualTo(403);
        assertThat(send(owner, post("/admin/v1/tags"), Map.of("id", "whale", "label", "Whale", "colour", "plain"))
                        .getStatus())
                .isEqualTo(200);

        var path = "/admin/v1/records/user/" + person + "/tags/";
        assertThat(mvc.perform(support.as(post(path + "nonsense")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(support.as(post(path + "whale")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Whale");
        assertThat(mvc.perform(support.as(post(path + "whale/remove")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("Whale");
        assertThat(jdbc.queryForList(
                        "select action from identity.audit_log where target_id = ? and action like 'tag.%' order by seq",
                        String.class, person.toString()))
                .containsExactly("tag.add", "tag.remove");
    }

    private JsonNode linked(TestAdmins.TestAdmin admin, UUID person) throws Exception {
        var answer = mvc.perform(admin.as(get("/admin/v1/users/" + person + "/linked-accounts")))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        return json.readTree(answer.getContentAsString());
    }

    @Test
    void linkedAccountsShowEachSignalMaskedAndOnlyToTrustAndSafety() throws Exception {
        var one = TestUsers.signUp(mvc).id();
        var two = TestUsers.signUp(mvc).id();
        var unique = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("update identity.users set email = ? where id = ?", "a.sha+x" + unique + "@gmail.com", one);
        jdbc.update("update identity.users set email = ? where id = ?", "asha+y" + unique + "@googlemail.com", two);
        jdbc.update("update identity.users set email = ? where id = ?", "asha" + unique + "@gmail.com", one);
        jdbc.update("update identity.users set email = ? where id = ?", "a.s.h.a" + unique + "+z@gmail.com", two);
        var vpa = "shared." + unique + "@okhdfc";
        for (var who : List.of(one, two)) {
            jdbc.update(
                    "insert into payouts.methods (id, user_id, vpa, state, checked_at) values (?, ?, ?, 'active', now())",
                    UUID.randomUUID(),
                    who,
                    vpa);
            jdbc.update(
                    "insert into identity.security_events (id, user_id, kind, address, device, at)"
                            + " values (?, ?, 'sign_in', '203.0.113.55', ?, now())",
                    UUID.randomUUID(),
                    who,
                    "device" + unique);
        }
        var trust = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var page = linked(trust, one);
        assertThat(page.toString()).contains(two.toString());
        var signals = page.get("accounts").get(0).get("signals").toString();
        assertThat(signals).contains("same_email", "same_upi", "same_device", "same_network");
        assertThat(signals).doesNotContain(vpa, "203.0.113.55");

        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(mvc.perform(support.as(get("/admin/v1/users/" + one + "/linked-accounts")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
