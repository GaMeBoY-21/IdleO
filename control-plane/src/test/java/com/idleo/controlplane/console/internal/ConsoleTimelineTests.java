package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.TestAdmins;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The one timeline (ADR 0052): every module's events merged, newest first, by kind, by cursor, and by permission. */
@ControlPlaneTest
class ConsoleTimelineTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    Environment environment;

    private final JsonMapper json = JsonMapper.builder().build();
    private People.Made person;

    @BeforeEach
    void aPersonWithAHistory() throws Exception {
        person = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "timeline" + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode timeline(TestAdmins.TestAdmin admin, String subject, UUID id, String... more) throws Exception {
        var request = get("/admin/v1/timeline").param("subject", subject).param("id", id.toString());
        for (int i = 0; i < more.length; i += 2) {
            request = request.param(more[i], more[i + 1]);
        }
        var answer = mvc.perform(admin.as(request)).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        return json.readTree(answer.getContentAsString());
    }

    private static HashSet<String> kinds(JsonNode page) {
        var kinds = new HashSet<String>();
        page.get("events").forEach(event -> kinds.add(event.get("kind").asString()));
        return kinds;
    }

    @Test
    void aPersonsEventsComeFromEveryModuleNewestFirst() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var page = timeline(owner, "user", person.ids().user());
        assertThat(kinds(page)).contains("security", "top_up", "session", "payout", "host", "invoice");
        var times = new ArrayList<String>();
        page.get("events").forEach(event -> times.add(event.get("at").asString()));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(page.get("kinds").toString()).contains("admin_action");
    }

    @Test
    void anAdminOnlySeesTheKindsTheyMayRead() throws Exception {
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var page = timeline(support, "user", person.ids().user());
        assertThat(kinds(page)).doesNotContain("payout", "admin_action");
        assertThat(page.get("kinds").toString()).doesNotContain("payout", "admin_action");
        // Asking for it by name changes nothing.
        assertThat(timeline(support, "user", person.ids().user(), "kinds", "payout")
                        .get("events"))
                .isEmpty();
    }

    @Test
    void itFiltersByKindAndPagesByCursorWithoutRepeatingOrMissingAnything() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var user = person.ids().user();
        assertThat(kinds(timeline(owner, "user", user, "kinds", "top_up"))).containsExactly("top_up");

        var whole = new ArrayList<String>();
        timeline(owner, "user", user, "limit", "200")
                .get("events")
                .forEach(event -> whole.add(event.get("key").asString()));
        assertThat(whole.size()).isGreaterThan(3);

        var paged = new ArrayList<String>();
        var page = timeline(owner, "user", user, "limit", "2");
        while (true) {
            page.get("events").forEach(event -> paged.add(event.get("key").asString()));
            if (page.get("nextCursor").isNull()) {
                break;
            }
            page = timeline(
                    owner,
                    "user",
                    user,
                    "limit",
                    "2",
                    "cursor",
                    page.get("nextCursor").asString());
        }
        assertThat(paged).isEqualTo(whole);
    }

    @Test
    void aSessionAndAPaymentHaveTheirOwnSteps() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        assertThat(kinds(timeline(owner, "session", person.ids().sessions().getFirst())))
                .contains("session_step");
        var topUp = jdbc.queryForObject(
                "select id from billing.topups where user_id = ? and payment_id is not null limit 1",
                UUID.class,
                person.ids().user());
        assertThat(kinds(timeline(owner, "payment", topUp))).contains("razorpay");
    }

    @Test
    void pagesAboutAPersonAreInTheAccessLog() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var user = person.ids().user();
        var host = person.ids().hosts().getFirst();
        var session = person.ids().sessions().getFirst();
        var topUp = jdbc.queryForObject(
                "select id from billing.topups where user_id = ? and payment_id is not null limit 1", UUID.class, user);
        for (var path : new String[] {
            "/admin/v1/users/" + user,
            "/admin/v1/hosts/" + host,
            "/admin/v1/sessions/" + session,
            "/admin/v1/payments/" + topUp
        }) {
            assertThat(mvc.perform(owner.as(get(path)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(path)
                    .isEqualTo(200);
        }
        assertThat(jdbc.queryForList(
                        "select record_id from identity.personal_data_access where admin_id = ? and subject_user_id = ?"
                                + " and field = 'view'",
                        String.class,
                        owner.id(),
                        user))
                .contains(user.toString(), host.toString(), session.toString(), topUp.toString());
    }
}
