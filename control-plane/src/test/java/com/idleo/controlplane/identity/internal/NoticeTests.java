package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The Terms and privacy notice (ADR 0037): accepted once per version, with the age confirmed the first time. */
@ControlPlaneTest
class NoticeTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LegalSettings legal;

    @Autowired
    IdGenerator ids;

    @Autowired
    MutableClock clock;

    private String body(boolean ageConfirmed) {
        return """
                {"termsVersion":"%s","privacyVersion":"%s","ageConfirmed":%s}
                """.formatted(legal.notices().termsVersion(), legal.notices().privacyVersion(), ageConfirmed);
    }

    @Test
    void aNewAccountAcceptsOnceWithItsAgeConfirmed() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.notices.accepted").value(false))
                .andExpect(jsonPath("$.notices.ageConfirmed").value(false))
                .andExpect(jsonPath("$.notices.minimumAge").value(18))
                .andExpect(
                        jsonPath("$.notices.termsVersion").value(legal.notices().termsVersion()));

        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/age-not-confirmed"));
        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsVersion\":\"old\",\"privacyVersion\":\"old\",\"ageConfirmed\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/notices-changed"));
        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices.accepted").value(true))
                .andExpect(jsonPath("$.notices.ageConfirmed").value(true));
        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(false)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList(
                        "select terms_version || '/' || privacy_version || '/' || age_confirmed"
                                + " from identity.notice_acceptances where user_id = ?",
                        String.class,
                        user.id()))
                .containsExactly(
                        legal.notices().termsVersion() + "/" + legal.notices().privacyVersion() + "/true");
    }

    @Test
    void aNewVersionIsAskedForAgainWithoutTheAge() {
        var user = UUID.randomUUID();
        jdbc.update(
                "insert into identity.users (id, status, email, email_verified, created_at, updated_at, version)"
                        + " values (?, 'active', null, true, now(), now(), 0)",
                user);
        var current = new NoticeAcceptances(JdbcClient.create(jdbc), ids, clock, legal);
        assertThat(current.accept(
                        user, legal.notices().termsVersion(), legal.notices().privacyVersion(), true))
                .isEqualTo(NoticeAcceptances.Outcome.ACCEPTED);

        var changed = new NoticeAcceptances(
                JdbcClient.create(jdbc),
                ids,
                clock,
                new LegalSettings(
                        legal.retention(),
                        legal.erasure(),
                        new LegalSettings.Notices("2027-01", legal.notices().privacyVersion(), 18),
                        legal.support(),
                        legal.grievance()));
        assertThat(changed.standing(user).accepted()).isFalse();
        assertThat(changed.standing(user).ageConfirmed()).isTrue();
        assertThat(changed.accept(user, "2027-01", legal.notices().privacyVersion(), false))
                .isEqualTo(NoticeAcceptances.Outcome.ACCEPTED);
        assertThat(changed.standing(user).accepted()).isTrue();
    }

    @Test
    void anApiKeyCantAcceptForAPerson() throws Exception {
        var user = TestUsers.signUp(mvc);
        var key = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"ci\",\"scope\":\"full\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
    }
}
