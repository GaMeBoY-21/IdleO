package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Notification choices (ADR 0024): everything on, except what a person turns off, and money and safety stay on. */
@ControlPlaneTest
class NotificationChoicesTests {

    private static final List<String> LOCKED = List.of(
            "stopped-for-credit",
            "host-lost",
            "machine-offline",
            "payout",
            "report-answered",
            "top-up-confirmed",
            "image-blocked",
            "new-device-sign-in",
            "account-and-data");

    @Autowired
    MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();
    private String bearer;

    @BeforeEach
    void signIn() throws Exception {
        bearer = TestUsers.signUp(mvc).bearer();
    }

    private MockHttpServletRequestBuilder replace(String items) {
        return put("/v1/me/notifications")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":" + items + "}");
    }

    private String entry(String kind, boolean email, boolean inApp) {
        return "{\"kind\":\"" + kind + "\",\"email\":" + email + ",\"inApp\":" + inApp + "}";
    }

    private String current() throws Exception {
        return mvc.perform(get("/v1/me/notifications").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private boolean switchOf(String body, String kind, String channel) {
        for (var item : json.readTree(body).get("items")) {
            if (item.get("kind").asString().equals(kind)) {
                return item.get(channel).asBoolean();
            }
        }
        throw new AssertionError("no kind " + kind);
    }

    @Test
    void everyKindIsOnByDefaultAndTheMoneyAndSafetyKindsAreLocked() throws Exception {
        var items = json.readTree(current()).get("items");

        assertThat(items).hasSize(16);
        var locked = new java.util.ArrayList<String>();
        for (var item : items) {
            assertThat(item.get("email").asBoolean()).isTrue();
            assertThat(item.get("inApp").asBoolean()).isTrue();
            assertThat(item.get("title").asString()).isNotBlank();
            if (item.get("locked").asBoolean()) {
                locked.add(item.get("kind").asString());
                assertThat(item.get("lockedReason").asString()).isNotBlank();
            } else {
                assertThat(item.get("lockedReason").isNull()).isTrue();
            }
        }
        assertThat(locked).containsExactlyInAnyOrderElementsOf(LOCKED);
    }

    @Test
    void aChoiceIsKeptAndOnlyForThatPerson() throws Exception {
        mvc.perform(replace(
                        "[" + entry("session-completed", false, true) + "," + entry("low-credit", true, false) + "]"))
                .andExpect(status().isOk());

        var mine = current();
        assertThat(switchOf(mine, "session-completed", "email")).isFalse();
        assertThat(switchOf(mine, "session-completed", "inApp")).isTrue();
        assertThat(switchOf(mine, "low-credit", "email")).isTrue();
        assertThat(switchOf(mine, "low-credit", "inApp")).isFalse();
        assertThat(switchOf(mine, "session-failed", "email")).isTrue();

        var other = TestUsers.signUp(mvc).bearer();
        var theirs = mvc.perform(get("/v1/me/notifications").header("Authorization", other))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(switchOf(theirs, "session-completed", "email")).isTrue();
    }

    @Test
    void aKindLeftOutIsOnAgainAndTheSameRequestTwiceChangesNothingMore() throws Exception {
        mvc.perform(replace("[" + entry("session-completed", false, false) + "]"))
                .andExpect(status().isOk());
        mvc.perform(replace("[" + entry("low-credit", false, true) + "]")).andExpect(status().isOk());
        var once = current();
        mvc.perform(replace("[" + entry("low-credit", false, true) + "]")).andExpect(status().isOk());

        assertThat(switchOf(once, "session-completed", "email")).isTrue();
        assertThat(switchOf(once, "low-credit", "email")).isFalse();
        assertThat(current()).isEqualTo(once);
        mvc.perform(replace("[]")).andExpect(status().isOk());
        assertThat(switchOf(current(), "low-credit", "email")).isTrue();
    }

    @Test
    void aLockedKindCantBeSwitchedOffByEmailOrInApp() throws Exception {
        for (var kind : LOCKED) {
            for (var choice : new boolean[][] {{false, true}, {true, false}, {false, false}}) {
                mvc.perform(replace("[" + entry("session-completed", false, true) + ","
                                + entry(kind, choice[0], choice[1]) + "]"))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value("/problems/notification-locked"))
                        .andExpect(jsonPath("$.errors[0].field").value("items[1].kind"));
            }
        }
        // Nothing in the refused requests was kept, not even the allowed change beside it.
        assertThat(switchOf(current(), "session-completed", "email")).isTrue();
    }

    @Test
    void aLockedKindMayBeSentOn() throws Exception {
        mvc.perform(replace("[" + entry("payout", true, true) + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind == 'payout')].email").value(true));
    }

    @Test
    void anUnknownRepeatedOrIncompleteChoiceIs400() throws Exception {
        mvc.perform(replace("[" + entry("spam", true, true) + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("items[0].kind"));
        mvc.perform(replace("[" + entry("low-credit", false, true) + "," + entry("low-credit", true, true) + "]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[1].kind"));
        mvc.perform(replace("[{\"kind\":\"low-credit\",\"email\":false}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        assertThat(switchOf(current(), "low-credit", "email")).isTrue();
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/v1/me/notifications")).andExpect(status().isUnauthorized());
    }
}
