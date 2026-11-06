package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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

    @Autowired
    List<Approvals.GatedAction> actions;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /**
     * What returns many of something and isn't a list of the console, each with why. Anything else that does is a list, and
     * goes through {@link AdminListing}.
     */
    private static final Map<String, String> NOT_LISTS = Map.ofEntries(
            Map.entry("/admin/v1/abuse/{id}", "one record"),
            Map.entry("/admin/v1/adjustments/{id}", "one record, with its legs"),
            Map.entry("/admin/v1/ledger/accounts", "the chart of accounts: one row an account, a fixed list"),
            Map.entry("/admin/v1/ledger/trial-balance", "one row an account, a fixed list"),
            Map.entry("/admin/v1/ledger/transactions/{id}", "one transaction, with its legs"),
            Map.entry("/admin/v1/money/report", "a report: one row a day, week or month, at most 400 days"),
            Map.entry("/admin/v1/money/liabilities", "one report, with a fixed sixteen months of breakage"),
            Map.entry("/admin/v1/periods", "the last few months, at most 24"),
            Map.entry("/admin/v1/periods/{month}", "one month, with its checklist and history"),
            Map.entry("/admin/v1/approvals/{id}", "one record"),
            Map.entry("/admin/v1/audit/standing", "the chain's state and its newest copies"),
            Map.entry("/admin/v1/chargebacks/{id}", "one record"),
            Map.entry("/admin/v1/disputes/{id}", "one record"),
            Map.entry("/admin/v1/hosts/{id}", "one record"),
            Map.entry("/admin/v1/hosts/{id}/history", "a record's fixed-window chart"),
            Map.entry("/admin/v1/images/{id}", "one record"),
            Map.entry("/admin/v1/me/sessions", "this admin's own, at most a handful"),
            Map.entry("/admin/v1/notes", "the notes on one record"),
            Map.entry("/admin/v1/notes/mentionable", "a picker's choices"),
            Map.entry("/admin/v1/passkeys", "this admin's own, at most a handful"),
            Map.entry("/admin/v1/appeals/{id}", "one appeal, with its history"),
            Map.entry("/admin/v1/queue/sla", "the Owner's few targets"),
            Map.entry("/admin/v1/queue/bulk/{id}", "one bulk action, item by item"),
            Map.entry("/admin/v1/payments/{id}", "one record"),
            Map.entry("/admin/v1/records/{subject}/{id}/tags", "the tags on one record"),
            Map.entry("/admin/v1/refunds/{id}", "one record"),
            Map.entry("/admin/v1/search", "the palette's groups, merged across modules"),
            Map.entry("/admin/v1/session", "this admin's own session"),
            Map.entry("/admin/v1/sessions/{id}", "one record"),
            Map.entry("/admin/v1/sessions/{id}/commands", "one session's few commands"),
            Map.entry("/admin/v1/sessions/{id}/life", "one record"),
            Map.entry("/admin/v1/sessions/{id}/log", "one job's log, in order, read by chunk"),
            Map.entry("/admin/v1/sessions/{id}/usage", "one record"),
            Map.entry("/admin/v1/tags", "the vocabulary, a picker's choices"),
            Map.entry("/admin/v1/tickets/{id}", "one thread"),
            Map.entry("/admin/v1/timeline", "merged across modules, with a cursor of its own (ADR 0052)"),
            Map.entry("/admin/v1/users/{id}/credit", "one record"),
            Map.entry("/admin/v1/users/{id}/deletion", "one record"),
            Map.entry("/admin/v1/users/{id}/linked-accounts", "one person's few matches"),
            Map.entry("/admin/v1/users/{id}/payout-details", "one record"),
            Map.entry("/admin/v1/views", "this admin's saved views"));

    private static boolean returnsMany(HandlerMethod handler) {
        var type = handler.getMethod().getReturnType();
        if (Collection.class.isAssignableFrom(type)) {
            return true;
        }
        return type.isRecord()
                && Arrays.stream(type.getRecordComponents())
                        .anyMatch(part -> part.getName().equals("nextCursor")
                                || Collection.class.isAssignableFrom(part.getType()));
    }

    private static Set<String> params(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters())
                .filter(parameter -> parameter.hasParameterAnnotation(RequestParam.class))
                .map(parameter -> {
                    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                    return parameter.getParameterName();
                })
                .collect(Collectors.toSet());
    }

    /** Every endpoint that returns many of something is a list on the one engine, or is written down above with why. */
    @Test
    void everyAdminListUsesTheOneEngine() {
        var names = listings.stream().map(AdminListing::name).collect(Collectors.toSet());
        var served = new HashSet<String>();
        var offenders = new ArrayList<String>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            var isGet = info.getMethodsCondition().getMethods().contains(RequestMethod.GET);
            info.getPatternValues().stream()
                    .filter(path -> path.startsWith("/admin/") && isGet && returnsMany(handler))
                    .forEach(path -> {
                        var declared = handler.getMethodAnnotation(AdminList.class);
                        if (declared == null) {
                            if (!NOT_LISTS.containsKey(path)) {
                                offenders.add(path + " returns many and isn't on the list engine");
                            }
                            return;
                        }
                        served.add(declared.value());
                        if (!names.contains(declared.value())) {
                            offenders.add(path + " names a list nothing declares: " + declared.value());
                        }
                        if (!params(handler).containsAll(Set.of("sort", "cursor", "limit"))) {
                            offenders.add(path + " can't be sorted and paged");
                        }
                    });
        });
        assertThat(offenders).isEmpty();
        assertThat(served).as("a list no endpoint serves").containsExactlyInAnyOrderElementsOf(names);
    }

    /** It's the engine that answers: a sort nothing declares is refused, on every list. */
    @Test
    void everyListRefusesASortItDoesNotHave() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var refused = new ArrayList<String>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (entry.getValue().getMethodAnnotation(AdminList.class) == null) {
                continue;
            }
            for (var path : entry.getKey().getPatternValues()) {
                var request = get(path.replace("{id}", owner.id().toString()))
                        .param("sort", "no-such-sort")
                        .param("account", "platform_revenue")
                        .param("host", UUID.randomUUID().toString())
                        .param("user", UUID.randomUUID().toString());
                var answer = mvc.perform(owner.as(request)).andReturn().getResponse();
                if (answer.getStatus() != 400 || !answer.getContentAsString().contains("sort")) {
                    refused.add(path + " answered " + answer.getStatus() + ": " + answer.getContentAsString());
                }
            }
        }
        assertThat(refused).isEmpty();
    }

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
        assertThat(export(finance, Map.of("list", "abuse", "reason", "for the board's count"))
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
    void aListWithPersonalDataIsFetchedOnceByWhoeverAskedOnceAnotherAdminApproves() throws Exception {
        var asker = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var approver = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var asked = export(asker, Map.of("list", "users", "reason", "for the board's count"));
        assertThat(asked.getStatus()).isEqualTo(202);
        var id = json.readTree(asked.getContentAsString()).get("id").asString();
        var approved = mvc.perform(approver.as(post("/admin/v1/approvals/" + id + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the board asked for it\"}"))
                .andReturn()
                .getResponse();
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);

        var file = mvc.perform(asker.as(post("/admin/v1/approvals/" + id + "/download")))
                .andReturn()
                .getResponse();
        assertThat(file.getStatus()).as(file.getContentAsString()).isEqualTo(200);
        assertThat(file.getContentType()).startsWith("text/csv");
        assertThat(file.getHeader("Content-Disposition")).contains("users.csv");
        assertThat(file.getContentAsString()).startsWith("id,");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'list.export' and actor_user_id = ?",
                        Integer.class,
                        asker.id()))
                .isEqualTo(1);
        assertThat(mvc.perform(asker.as(post("/admin/v1/approvals/" + id + "/download")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
    }

    /** The approval covers the query: the file is what the list holds when it's fetched, written a row at a time. */
    @Test
    void anApprovedExportIsWrittenRowByRowWhenItsFetched() throws Exception {
        var note = "streamed " + UUID.randomUUID();
        var invite = "insert into identity.invite_codes (id, code_hash, note, created_by, created_at)"
                + " values (?, ?, ?, 'test', now())";
        for (int i = 0; i < 3; i++) {
            jdbc.update(invite, UUID.randomUUID(), UUID.randomUUID().toString().getBytes(), note);
        }
        var export = actions.stream()
                .filter(action -> action.kind().equals("list.export"))
                .findFirst()
                .orElseThrow();
        var file = export.download(new Approvals.Run(
                        UUID.randomUUID(),
                        json.readTree("{\"list\":\"invites\",\"reason\":\"counting the invites\"}"),
                        "counting the invites",
                        new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, UUID.randomUUID(), "test")))
                .orElseThrow();

        jdbc.update(invite, UUID.randomUUID(), UUID.randomUUID().toString().getBytes(), note);

        var writes = new AtomicInteger();
        var sent = new ByteArrayOutputStream();
        file.writer().writeTo(new FilterOutputStream(sent) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                writes.incrementAndGet();
                out.write(bytes, offset, length);
            }

            @Override
            public void write(byte[] bytes) throws IOException {
                write(bytes, 0, bytes.length);
            }
        });
        var lines = sent.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines.stream().filter(line -> line.contains("streamed")).count())
                .isEqualTo(4);
        assertThat(writes.get()).isEqualTo(lines.size());
    }

    @Test
    void aTagOnAListThatCantCarryOneIsRefused() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var answer =
                export(owner, Map.of("list", "audit", "filters", Map.of("tag", "vip"), "reason", "reading the log"));
        assertThat(answer.getStatus()).isEqualTo(400);
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
