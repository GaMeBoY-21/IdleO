package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A person's list of messages: newest first, a page at a time, read in one call, and theirs alone (ADR 0026). */
@ControlPlaneTest
class InboxApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    Notifier notifier;

    private TestUsers.TestUser person;

    @BeforeEach
    void signIn() throws Exception {
        person = TestUsers.signUp(mvc);
    }

    private void tell(TestUsers.TestUser user, String title) {
        notifier.tell(
                user.id(),
                NotificationKind.SESSION_COMPLETED,
                "inbox-test:" + UUID.randomUUID(),
                new Message(title, "Body of " + title, "/session/?id=" + UUID.randomUUID()));
    }

    private JsonNode list(TestUsers.TestUser user, String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/notifications" + query).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private int unreadOnMe(TestUsers.TestUser user) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("unreadNotifications")
                .asInt();
    }

    @Test
    void aNewPersonHasNothingAndNothingUnread() throws Exception {
        var page = list(person, "");

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("unread").asInt()).isZero();
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(unreadOnMe(person)).isZero();
    }

    @Test
    void messagesComeNewestFirstWithTheUnreadCountOnTheListAndOnMe() throws Exception {
        tell(person, "first");
        tell(person, "second");
        tell(person, "third");

        var page = list(person, "");

        assertThat(page.get("items").size()).isEqualTo(3);
        assertThat(page.get("items").get(0).get("title").asString()).isEqualTo("third");
        assertThat(page.get("items").get(2).get("title").asString()).isEqualTo("first");
        assertThat(page.get("items").get(0).get("read").asBoolean()).isFalse();
        assertThat(page.get("items").get(0).get("kind").asString()).isEqualTo("session-completed");
        assertThat(page.get("items").get(0).get("link").asString()).startsWith("/session/?id=");
        assertThat(page.get("unread").asInt()).isEqualTo(3);
        assertThat(unreadOnMe(person)).isEqualTo(3);
    }

    @Test
    void pagesFollowTheCursorToTheEnd() throws Exception {
        for (var title : new String[] {"a", "b", "c", "d", "e"}) {
            tell(person, title);
        }

        var first = list(person, "?limit=2");
        var second = list(person, "?limit=2&cursor=" + first.get("nextCursor").asString());
        var third = list(person, "?limit=2&cursor=" + second.get("nextCursor").asString());

        assertThat(titles(first)).containsExactly("e", "d");
        assertThat(titles(second)).containsExactly("c", "b");
        assertThat(titles(third)).containsExactly("a");
        assertThat(third.get("nextCursor").isNull()).isTrue();
        assertThat(third.get("unread").asInt()).isEqualTo(5);
    }

    private static java.util.List<String> titles(JsonNode page) {
        var titles = new java.util.ArrayList<String>();
        page.get("items").forEach(item -> titles.add(item.get("title").asString()));
        return titles;
    }

    @Test
    void markingReadUpToOneLeavesTheNewerOnesUnread() throws Exception {
        tell(person, "old");
        tell(person, "middle");
        tell(person, "new");
        var items = list(person, "").get("items");

        var middle = items.get(1).get("id").asString();
        mvc.perform(post("/v1/notifications/read")
                        .header("Authorization", person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upTo\":\"" + middle + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        var after = list(person, "").get("items");
        assertThat(after.get(0).get("read").asBoolean()).as("new").isFalse();
        assertThat(after.get(1).get("read").asBoolean()).as("middle").isTrue();
        assertThat(after.get(2).get("read").asBoolean()).as("old").isTrue();
        assertThat(unreadOnMe(person)).isEqualTo(1);
    }

    @Test
    void markAllReadWithTheNewestIdAndAgainChangesNothing() throws Exception {
        tell(person, "one");
        tell(person, "two");
        var newest = list(person, "").get("items").get(0).get("id").asString();
        var body = "{\"upTo\":\"" + newest + "\"}";

        for (int times = 0; times < 2; times++) {
            mvc.perform(post("/v1/notifications/read")
                            .header("Authorization", person.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread").value(0));
        }
        assertThat(unreadOnMe(person)).isZero();
    }

    @Test
    void aPersonSeesAndMarksOnlyTheirOwn() throws Exception {
        var other = TestUsers.signUp(mvc);
        tell(other, "theirs");
        tell(person, "mine");
        var theirs = list(other, "").get("items").get(0).get("id").asString();

        mvc.perform(post("/v1/notifications/read")
                        .header("Authorization", person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upTo\":\"" + theirs + "\"}"))
                .andExpect(status().isOk())
                // Their message is older than mine, so marking up to it leaves mine alone.
                .andExpect(jsonPath("$.unread").value(1));

        assertThat(titles(list(person, ""))).containsExactly("mine");
        assertThat(list(other, "").get("unread").asInt())
                .as("theirs stays unread")
                .isEqualTo(1);
    }

    @Test
    void aBadCursorOrLimitIsAValidationProblem() throws Exception {
        mvc.perform(get("/v1/notifications?cursor=not-a-cursor").header("Authorization", person.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
        mvc.perform(get("/v1/notifications?limit=0").header("Authorization", person.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(post("/v1/notifications/read")
                        .header("Authorization", person.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListNeedsASignIn() throws Exception {
        mvc.perform(get("/v1/notifications")).andExpect(status().isUnauthorized());
    }
}
