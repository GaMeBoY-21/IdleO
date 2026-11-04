package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.uuid.Generators;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Masked by default (ADR 0051): a person with known personal data in every table, every admin list and detail endpoint
 * read as an Owner, and none of it may appear whole. New GET endpoints join by themselves; one this test can't reach
 * fails until it's given a record to read.
 */
@ControlPlaneTest
class AdminMaskingTests {

    /** What has no JSON to mask: a document people are entitled to as issued, recorded by its own permission. */
    private static final Set<String> FILES = Set.of("/admin/v1/invoices/{id}/pdf");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    Environment environment;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** The first row a query finds, as text, to read one endpoint's detail with. */
    private String any(String sql, Object... args) {
        var found = jdbc.queryForList(sql, String.class, args);
        return found.isEmpty() ? UUID.randomUUID().toString() : found.getFirst();
    }

    @Test
    void noPersonalDataAppearsWholeInAnyAdminResponse() throws Exception {
        var marker = "maskme" + UUID.randomUUID().toString().substring(0, 8);
        var person = People.make(mvc, jdbc, environment, clock, marker);
        var user = person.ids().user();
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var ip = "203.0.113.77";
        var phone = "9876543210";
        var pan = "ABCPE9999F";
        var upi = "known.person@okhdfc";
        var replyTo = "known.person@example.com";

        // The values this test looks for, in the places an admin reads them.
        jdbc.update("update identity.security_events set address = cast(? as inet) where user_id = ?", ip, user);
        jdbc.update(
                "insert into identity.security_events (id, user_id, kind, address, at) values (?, ?, 'sign_in', cast(? as inet), now())",
                Generators.timeBasedEpochGenerator().generate(),
                user,
                ip);
        jdbc.update("update payouts.methods set vpa = ? where user_id = ?", upi, user);
        var ticket = Generators.timeBasedEpochGenerator().generate();
        jdbc.update(
                "insert into support.tickets (id, user_id, topic, status, reply_to, created_at, updated_at, due_at)"
                        + " values (?, ?, 'privacy', 'open', ?, now(), now(), now() + interval '30 days')",
                ticket,
                user,
                replyTo);
        jdbc.update(
                "insert into support.messages (id, ticket_id, author, body, created_at) values (?, ?, 'person', ?, now())",
                Generators.timeBasedEpochGenerator().generate(),
                ticket,
                "Call me on " + phone + ", my PAN is " + pan + ", write to " + person.email() + " or from " + ip);
        jdbc.update("update trust.ratings set comment = ? where renter_id = ?", "reach me at " + person.email(), user);
        mvc.perform(admin.as(post("/admin/v1/invites"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":1,\"note\":\"for the masking test\"}"));

        var session = person.ids().sessions().getFirst();
        var host = person.ids().hosts().getFirst();

        // Where each detail endpoint finds its record: the person's where they have one.
        var ids = new LinkedHashMap<String, String>();
        ids.put("/admin/v1/users/{id}", user.toString());
        ids.put("/admin/v1/hosts/{id}", host.toString());
        ids.put("/admin/v1/sessions/{id}", session.toString());
        ids.put("/admin/v1/tickets/{id}", ticket.toString());
        ids.put("/admin/v1/disputes/{id}", any("select id::text from trust.disputes where renter_id = ?", user));
        ids.put("/admin/v1/abuse/{id}", any("select id::text from trust.abuse_items where renter_id = ?", user));
        ids.put("/admin/v1/images/{id}", any("select id::text from images.images where user_id = ?", user));
        ids.put("/admin/v1/refunds/{id}", any("select id::text from billing.credit_refunds where user_id = ?", user));
        ids.put("/admin/v1/invoices/{id}", any("select id::text from billing.invoices where user_id = ?", user));
        ids.put("/admin/v1/chargebacks/{id}", any("select id::text from billing.chargebacks where user_id = ?", user));

        ids.put(
                "/admin/v1/approvals/{id}",
                any("select id::text from identity.approval_requests where params::text like ?", "%" + user + "%"));

        var parameters = Map.of(
                "/admin/v1/users", "query=" + marker,
                "/admin/v1/payments", "query=" + user,
                "/admin/v1/credit-grants", "user=" + user,
                "/admin/v1/ledger", "account=renter_wallet&owner=" + user,
                "/admin/v1/sessions/{id}/log", "reason=checking the masking",
                "/admin/v1/hosts", "query=" + host,
                "/admin/v1/disputes/host-flags", "host=" + host,
                "/admin/v1/ratings", "session=" + session,
                "/admin/v1/personal-data/access-log", "subject=" + user);

        var whole = Set.of(person.email(), upi, replyTo, phone, pan, ip);
        var read = new ArrayList<String>();
        var problems = new ArrayList<String>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var declared = entry.getValue().getMethodAnnotation(RequiresPermission.class);
            if (declared == null || declared.unmasked()) {
                continue;
            }
            if (entry.getKey().getMethodsCondition().getMethods().stream()
                    .noneMatch(method -> method.name().equals("GET"))) {
                continue;
            }
            for (var path : entry.getKey().getPatternValues()) {
                if (FILES.contains(path)) {
                    continue;
                }
                var address = ids.getOrDefault(path, null) != null
                        ? path.replace("{id}", ids.get(path))
                        : path.replace(
                                "{id}",
                                path.contains("/users/")
                                        ? user.toString()
                                        : path.contains("/hosts/") ? host.toString() : session.toString());
                var query = parameters.get(path);
                var response = mvc.perform(admin.as(get(query == null ? address : address + "?" + query)))
                        .andReturn()
                        .getResponse();
                var body = response.getContentAsString();
                // A session that never metered has no usage to read: its 404 is the answer, and masks nothing away.
                if (response.getStatus() >= 300 && !path.endsWith("/usage")) {
                    problems.add(path + " answered " + response.getStatus() + ": " + body);
                }
                for (var value : whole) {
                    if (body.contains(value)) {
                        problems.add(path + " shows " + value + " whole: " + body);
                    }
                }
                read.add(path);
            }
        }
        assertThat(problems).isEmpty();
        // Every list and detail endpoint there is: this is the coverage the report gives.
        assertThat(read).hasSizeGreaterThanOrEqualTo(40);
        System.out.println("masking test read " + read.size() + " admin endpoints");

        // And the values really were there to find, masked.
        var thread = mvc.perform(admin.as(get("/admin/v1/tickets/" + ticket)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(thread).contains("kn•••@example.com", "•••10", "•••999F", "203.0.113.0/24");
        var verifications =
                jdbc.queryForObject("select count(*) from payouts.methods where vpa = ?", Integer.class, upi);
        assertThat(verifications).isPositive();
    }

    private String revealBody(String field, Object id, String reason) {
        return json.writeValueAsString(Map.of("field", field, "id", id.toString(), "reason", reason));
    }

    @Test
    void aRevealGivesTheWholeValueAndIsLoggedWithWhoWhoseWhichWhyAndFromWhere() throws Exception {
        var person = TestUsers.signUp(mvc);
        var email = jdbc.queryForObject("select email from identity.users where id = ?", String.class, person.id());
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var answer = mvc.perform(admin.as(post("/admin/v1/personal-data/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revealBody("email", person.id(), "answering ticket about refunds")))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(json.readTree(answer.getContentAsString()).get("value").asString())
                .isEqualTo(email);
        var row = jdbc.queryForMap(
                "select admin_id, field, reason, admin_session_id from identity.personal_data_access"
                        + " where subject_user_id = ? and field = 'email'",
                person.id());
        assertThat(row).containsEntry("admin_id", admin.id()).containsEntry("reason", "answering ticket about refunds");
        assertThat(row.get("admin_session_id")).isNotNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'personal_data.reveal' and target_id = ?",
                        Integer.class,
                        person.id().toString()))
                .isEqualTo(1);
    }

    @Test
    void aRevealNeedsAReasonOfTenCharactersOrATicketNumber() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        for (var refused : new String[] {"too short", "#12a", " "}) {
            assertThat(mvc.perform(admin.as(post("/admin/v1/personal-data/reveal"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(revealBody("email", person.id(), refused)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(refused)
                    .isEqualTo(400);
        }
        assertThat(mvc.perform(admin.as(post("/admin/v1/personal-data/reveal"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(revealBody("email", person.id(), "#142")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(admin.as(post("/admin/v1/personal-data/reveal"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(revealBody("no-such-field", person.id(), "a good long reason")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void openingAPersonsPageIsLoggedAndOnlyAuditReadersSeeTheLog() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(get("/admin/v1/users/" + person.id())));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.personal_data_access where subject_user_id = ? and field = 'view'",
                        Integer.class,
                        person.id()))
                .isEqualTo(1);
        var log = json.readTree(mvc.perform(admin.as(get("/admin/v1/personal-data/access-log")
                                .param("subject", person.id().toString())))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("entries");
        assertThat(log).hasSize(1);
        assertThat(log.get(0).get("field").asString()).isEqualTo("view");

        var auditor = TestAdmins.signInAs(mvc, jdbc, clock, "auditor");
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(mvc.perform(auditor.as(get("/admin/v1/personal-data/access-log")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(support.as(get("/admin/v1/personal-data/access-log")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }
}
