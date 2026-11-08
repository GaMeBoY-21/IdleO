package com.idleo.controlplane.queues.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bulk actions from any list (ADR 0057): what each list allows, and the inbox's engine running on another list's rows. */
@ControlPlaneTest
class BulkFromAnyListTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Every list, and exactly what it allows. A new list, or a change to one, is a decision made here. */
    private static final Map<String, Set<String>> ALLOWED = new TreeMap<>(Map.ofEntries(
            Map.entry("inbox", Set.of("assign", "tag", "priority", "close", "macro", "warn")),
            Map.entry("users", Set.of("tag", "warn")),
            Map.entry("hosts", Set.of("tag", "warn")),
            Map.entry("fleet", Set.of("tag", "warn")),
            Map.entry("sessions", Set.of("tag", "warn")),
            Map.entry("abuse", Set.of("tag", "warn")),
            Map.entry("reports", Set.of("tag", "warn")),
            Map.entry("ratings", Set.of("tag", "warn")),
            Map.entry("images", Set.of("tag", "warn")),
            Map.entry("tickets", Set.of("tag", "warn")),
            Map.entry("payments", Set.of("tag")),
            Map.entry("refunds", Set.of("tag")),
            Map.entry("invoices", Set.of("tag")),
            Map.entry("payouts", Set.of("tag")),
            Map.entry("chargebacks", Set.of("tag")),
            Map.entry("clawbacks", Set.of("tag"))));

    /** The kinds that touch neither money nor enforcement past a warning: the only ones a bulk action may ever do. */
    private static final Set<String> NEVER_MONEY = Set.of("assign", "tag", "priority", "close", "macro", "warn");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<AdminListing> listings;

    @Autowired
    BulkActions bulk;

    @Autowired
    AdminTagged tagged;

    private TestAdmins.TestAdmin owner;

    @BeforeEach
    void anOwnerAndATag() throws Exception {
        owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        jdbc.update("insert into console.tags (id, label, colour, created_at) values ('any-list', 'Any list', 'plain',"
                + " now()) on conflict do nothing");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void everyListSaysWhatItAllowsAndNothingElseHasAny() {
        var actual = new TreeMap<String, Set<String>>();
        var without = new ArrayList<String>();
        for (var listing : listings) {
            if (listing.bulkActions().isEmpty()) {
                assertThat(listing.noBulkBecause()).as(listing.name()).isNotBlank();
                without.add(listing.name());
            } else {
                assertThat(listing.noBulkBecause()).as(listing.name()).isNull();
                assertThat(listing.bulkActions()).as(listing.name()).isSubsetOf(NEVER_MONEY);
                actual.put(listing.name(), listing.bulkActions());
            }
        }
        assertThat(actual).isEqualTo(ALLOWED);
        System.out.println("bulk: " + actual.size() + " lists allow actions: " + actual);
        System.out.println("bulk: " + without.size() + " lists allow none: " + without);
        assertThat(actual.size() + without.size()).isEqualTo(listings.size());
    }

    private JsonNode send(String path, Object body, int expected) throws Exception {
        var response = mvc.perform(owner.as(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return JSON.readTree(response.getContentAsString());
    }

    private static Map<String, Object> tag() {
        return Map.of("kind", "tag", "tag", "any-list");
    }

    private static Map<String, Object> asked(String list, List<UUID> items, Object action) {
        return Map.of("list", list, "items", items, "action", action);
    }

    @Test
    void aTagFromTheUsersListPreviewsRunsAndCanBeUndone() throws Exception {
        var one = TestUsers.signUp(mvc).id();
        var other = TestUsers.signUp(mvc).id();
        tagged.add("user", other, "any-list", owner.id());

        var preview = send("/admin/v1/queue/bulk/preview", asked("users", List.of(one, other), tag()), 200);
        assertThat(preview.get("willChange").asInt()).isEqualTo(1);
        assertThat(preview.get("unchanged").asInt()).isEqualTo(1);
        assertThat(preview.toString()).doesNotContain("@");

        var started = send(
                "/admin/v1/queue/bulk",
                Map.of(
                        "list", "users",
                        "items", List.of(one, other),
                        "action", tag(),
                        "confirm", "2",
                        "reason", "Tagging from the users list"),
                200);
        bulk.run();
        var id = started.get("id").asString();
        var done = JSON.readTree(mvc.perform(owner.as(get("/admin/v1/queue/bulk/" + id)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(done.get("done").asInt()).isEqualTo(1);
        assertThat(done.get("skipped").asInt()).isEqualTo(1);
        assertThat(done.get("undoable").asBoolean()).isTrue();
        assertThat(tagged.carries("user", one, "any-list")).isTrue();

        send("/admin/v1/queue/bulk/" + id + "/undo", Map.of("reason", "Tagged the wrong people"), 200);
        assertThat(tagged.carries("user", one, "any-list")).isFalse();
        // Only what this batch changed goes back: the one tagged before it keeps its tag.
        assertThat(tagged.carries("user", other, "any-list")).isTrue();
    }

    @Test
    void aListRefusesWhatItDoesntAllowAndAListWithNoneSaysWhy() throws Exception {
        var user = TestUsers.signUp(mvc).id();
        var refused = send("/admin/v1/queue/bulk/preview", asked("users", List.of(user), Map.of("kind", "close")), 400);
        assertThat(refused.toString()).contains("allows only: tag, warn");
        var money = send(
                "/admin/v1/queue/bulk/preview",
                asked("payments", List.of(user), Map.of("kind", "warn", "template", "renter-warn")),
                400);
        assertThat(money.toString()).contains("allows only: tag");
        var none = send("/admin/v1/queue/bulk/preview", asked("ledger", List.of(user), tag()), 400);
        assertThat(none.toString()).contains("money is never bulk");
        send("/admin/v1/queue/bulk/preview", asked("no-such-list", List.of(user), tag()), 404);
        send("/admin/v1/queue/bulk/preview", asked("users", List.of(UUID.randomUUID()), tag()), 404);
    }

    @Test
    void overTwentyRowsOfAnyListGoToAnotherAdminFirst() throws Exception {
        var many = new ArrayList<UUID>();
        for (int i = 0; i < 21; i++) {
            many.add(TestUsers.signUp(mvc).id());
        }
        var answered = send(
                "/admin/v1/queue/bulk",
                Map.of(
                        "list", "users",
                        "items", many,
                        "action", tag(),
                        "confirm", "21",
                        "reason", "Tagging many from the users list"),
                202);
        assertThat(answered.get("kind").asString()).isEqualTo("queue.bulk");
        assertThat(tagged.carries("user", many.getFirst(), "any-list")).isFalse();
    }

    @Test
    void theConsoleIsToldWhatEachListItCanReadAllows() throws Exception {
        var lists = JSON.readTree(mvc.perform(owner.as(get("/admin/v1/queue/bulk/lists")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("lists");
        var seen = new TreeMap<String, JsonNode>();
        lists.forEach(row -> seen.put(row.get("list").asString(), row));
        assertThat(seen.get("users").get("actions").toString()).contains("tag", "warn");
        assertThat(seen.get("ledger").get("actions")).isEmpty();
        assertThat(seen.get("ledger").get("why").asString()).contains("money");
    }
}
