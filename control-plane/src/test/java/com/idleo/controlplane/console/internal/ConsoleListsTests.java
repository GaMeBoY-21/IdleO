package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The console's lists (ADR 0052): one engine, sorted by keyset, with exports and saved views. */
@ControlPlaneTest
class ConsoleListsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<AdminListing> listings;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode read(TestAdmins.TestAdmin admin, String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        var answer = mvc.perform(admin.as(request)).andReturn().getResponse();
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return json.readTree(answer.getContentAsString());
    }

    private MockHttpServletResponse export(TestAdmins.TestAdmin admin, Map<String, Object> body) throws Exception {
        return mvc.perform(admin.as(post("/admin/v1/exports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    @Test
    void everyListIsDeclaredOnceWithACsv() {
        assertThat(listings.stream().map(AdminListing::name))
                .doesNotHaveDuplicates()
                .contains(
                        "users",
                        "hosts",
                        "sessions",
                        "payments",
                        "refunds",
                        "invoices",
                        "payouts",
                        "tickets",
                        "reports",
                        "abuse",
                        "images",
                        "chargebacks",
                        "clawbacks",
                        "ratings",
                        "invites");
        assertThat(listings).allSatisfy(listing -> assertThat(listing.header()).isNotEmpty());
    }

    @Test
    void aSortedListPagesByKeysetWithoutRepeatingOrMissingARow() throws Exception {
        var marker = "sortme" + UUID.randomUUID().toString().substring(0, 8);
        var names = new ArrayList<String>();
        for (var letter : List.of("c", "a", "e", "b", "d")) {
            var person = TestUsers.signUp(mvc).id();
            names.add(marker + letter);
            jdbc.update("update identity.users set display_name = ? where id = ?", marker + letter, person);
        }
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");

        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            var page = cursor == null
                    ? read(admin, "/admin/v1/users", "query", marker, "sort", "name:asc", "limit", "2")
                    : read(
                            admin,
                            "/admin/v1/users",
                            "query",
                            marker,
                            "sort",
                            "name:asc",
                            "limit",
                            "2",
                            "cursor",
                            cursor);
            page.get("users").forEach(user -> seen.add(user.get("displayName").asString()));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactly(marker + "a", marker + "b", marker + "c", marker + "d", marker + "e");

        var bad = mvc.perform(admin.as(get("/admin/v1/users").param("sort", "password")))
                .andReturn()
                .getResponse();
        assertThat(bad.getStatus()).isEqualTo(400);
    }

    @Test
    void aCursorOfAnotherOrderIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            TestUsers.signUp(mvc);
        }
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var cursor = read(admin, "/admin/v1/users", "sort", "email:asc", "limit", "1")
                .get("nextCursor")
                .asString();
        assertThat(mvc.perform(admin.as(
                                get("/admin/v1/users").param("sort", "name:asc").param("cursor", cursor)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void aTagFiltersAList() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(mvc.perform(admin.as(post("/admin/v1/records/user/" + person + "/tags/partner")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        var found = read(admin, "/admin/v1/users", "tag", "partner", "limit", "200");
        assertThat(found.get("users").toString()).contains(person.toString());
        assertThat(found.get("users").size()).isLessThan(200);
        var none = read(admin, "/admin/v1/users", "tag", "no-such-tag");
        assertThat(none.get("users")).isEmpty();
    }

    @Test
    void aListWithoutPersonalDataExportsMaskedAfterAFactorAndIsLogged() throws Exception {
        jdbc.update(
                "insert into identity.invite_codes (id, code_hash, note, created_by, created_at)"
                        + " values (?, ?, 'for known.person@example.com', 'test', now())",
                UUID.randomUUID(),
                UUID.randomUUID().toString().getBytes());
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var file = export(admin, Map.of("list", "invites", "reason", "counting the invites"));
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).startsWith("text/csv");
        assertThat(file.getContentAsString()).startsWith("id,note,").doesNotContain("known.person@example.com");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.personal_data_access where admin_id = ? and field = 'export'",
                        Integer.class,
                        admin.id()))
                .isPositive();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'list.export' and actor_user_id = ?",
                        Integer.class,
                        admin.id()))
                .isPositive();
    }

    @Test
    void aListWithPersonalDataWaitsForAnotherAdminAndAnAdminWithoutItsPermissionIsRefused() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        assertThat(export(owner, Map.of("list", "users", "reason", "for the board's count"))
                        .getStatus())
                .isEqualTo(202);
        var finance = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        assertThat(export(finance, Map.of("list", "users", "reason", "for the board's count"))
                        .getStatus())
                .isEqualTo(403);
        assertThat(export(owner, Map.of("list", "nothing", "reason", "for the board's count"))
                        .getStatus())
                .isEqualTo(404);
        assertThat(export(
                                owner,
                                Map.of(
                                        "list",
                                        "invites",
                                        "filters",
                                        Map.of("nonsense", "x"),
                                        "reason",
                                        "for the count"))
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void aCellCantBecomeAFormula() {
        assertThat(AdminListExportController.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(AdminListExportController.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AdminListExportController.cell(null)).isEmpty();
    }

    @Test
    void savedViewsArePrivateOrSharedAndOnlyTheirOwnerDeletesThem() throws Exception {
        var one = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var two = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var list = "users-" + UUID.randomUUID().toString().substring(0, 8);
        for (var shared : List.of(true, false)) {
            assertThat(mvc.perform(one.as(post("/admin/v1/views"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(Map.of(
                                            "list",
                                            list,
                                            "name",
                                            shared ? "Everyone's" : "Mine",
                                            "shared",
                                            shared,
                                            "query",
                                            "status=active"))))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(200);
        }
        assertThat(read(one, "/admin/v1/views", "list", list).get("views")).hasSize(2);
        var seen = read(two, "/admin/v1/views", "list", list).get("views");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("name").asString()).isEqualTo("Everyone's");
        var id = seen.get(0).get("id").asString();
        assertThat(mvc.perform(two.as(MockMvcRequestBuilders.delete("/admin/v1/views/" + id)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(one.as(MockMvcRequestBuilders.delete("/admin/v1/views/" + id)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isLessThan(300);
    }
}
