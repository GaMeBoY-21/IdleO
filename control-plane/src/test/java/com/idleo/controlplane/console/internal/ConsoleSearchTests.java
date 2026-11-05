package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.TestAdmins;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console's search box (ADR 0052): what it finds by each kind of value, that an admin only ever hears of the kinds
 * they may read, that personal values match only whole, that results are masked, and that each search is logged masked.
 */
@ControlPlaneTest
class ConsoleSearchTests {

    private static final Set<String> KINDS = Set.of(
            "user", "host", "session", "payment", "refund", "invoice", "payout", "ticket", "image", "upi", "pan");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    Environment environment;

    @Autowired
    List<AdminSearch> providers;

    private final JsonMapper json = JsonMapper.builder().build();
    private People.Made person;
    private Map<String, String> values;

    @BeforeEach
    void aPersonWithSomethingOfEveryKind() throws Exception {
        person = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "search" + UUID.randomUUID().toString().substring(0, 8));
        var user = person.ids().user();
        var ticket = UUID.randomUUID();
        jdbc.update(
                "insert into support.tickets (id, user_id, topic, status, created_at, updated_at, due_at)"
                        + " values (?, ?, 'privacy', 'open', now(), now(), now() + interval '30 days')",
                ticket,
                user);
        var upi = "search." + UUID.randomUUID().toString().substring(0, 8) + "@okhdfc";
        jdbc.update("update payouts.methods set vpa = ? where user_id = ?", upi, user);
        var refundId = UUID.randomUUID();
        var topUp = jdbc.queryForObject(
                "select id from billing.topups where user_id = ? and payment_id is not null limit 1", UUID.class, user);
        var razorpayRefund =
                "rfnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.update(
                "insert into billing.credit_refunds (id, user_id, cause, requested_paise, status, requested_at)"
                        + " values (?, ?, 'request', 100, 'requested', now())",
                refundId,
                user);
        jdbc.update(
                "insert into billing.refund_parts (id, refund_id, topup_id, payment_id, lot_id, amount_paise, receipt,"
                        + " razorpay_refund_id, status, created_at, updated_at) select ?, ?, id, payment_id, ?, 100, ?,"
                        + " ?, 'pending', now(), now() from billing.topups where id = ?",
                UUID.randomUUID(),
                refundId,
                UUID.randomUUID(),
                razorpayRefund,
                razorpayRefund,
                topUp);
        var image = UUID.randomUUID();
        var digest = "sha256:" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        jdbc.update(
                "insert into images.images (id, user_id, reference, registry, repository, private, digest, state,"
                        + " attempts, done_bytes, total_bytes, created_at, updated_at)"
                        + " values (?, ?, 'docker.io/acme/trainer:1', 'docker.io', 'acme/trainer', false, ?, 'queued',"
                        + " 0, 0, 0, now(), now())",
                image,
                user,
                digest);
        var payout = jdbc.queryForObject("select id from payouts.payouts where user_id = ? limit 1", UUID.class, user);
        var razorpayx = "pout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.update("update payouts.payouts set razorpayx_id = ? where id = ?", razorpayx, payout);
        values = new LinkedHashMap<>();
        values.put("user", person.email());
        values.put("host", person.ids().hosts().getFirst().toString());
        values.put("session", person.ids().sessions().getFirst().toString());
        values.put(
                "payment",
                jdbc.queryForObject("select payment_id from billing.topups where id = ?", String.class, topUp));
        values.put("refund", razorpayRefund);
        values.put(
                "invoice",
                jdbc.queryForObject(
                        "select number from billing.invoices where user_id = ? limit 1", String.class, user));
        values.put("payout", razorpayx);
        values.put(
                "ticket",
                "#" + jdbc.queryForObject("select number from support.tickets where id = ?", Long.class, ticket));
        values.put("image", digest);
        values.put("upi", upi);
        values.put("pan", "abcpe4321f");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode search(TestAdmins.TestAdmin admin, String text) throws Exception {
        var answer = mvc.perform(admin.as(get("/admin/v1/search").param("q", text)))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).as(text).isEqualTo(200);
        return json.readTree(answer.getContentAsString());
    }

    private static Set<String> kinds(JsonNode results) {
        var kinds = new HashSet<String>();
        results.get("groups").forEach(group -> kinds.add(group.get("type").asString()));
        return kinds;
    }

    private Map<String, AdminPermission> permissionOfEachKind() {
        var each = new HashMap<String, AdminPermission>();
        providers.forEach(provider -> each.put(provider.type(), provider.permission()));
        return each;
    }

    private Set<String> permissionsOfRole(String role) {
        return new HashSet<>(jdbc.queryForList(
                "select permission from identity.admin_role_permissions where role_id = ?", String.class, role));
    }

    /** One test per role: each kind's value finds it exactly when the role holds that kind's permission. */
    @ParameterizedTest
    @ValueSource(strings = {"owner", "operations", "support", "trust_safety", "finance", "auditor"})
    void anAdminOnlyHearsOfTheKindsTheyMayRead(String role) throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, role);
        var permissions = permissionOfEachKind();
        assertThat(permissions.keySet()).containsExactlyInAnyOrderElementsOf(KINDS);
        var held = permissionsOfRole(role);
        var wrong = new ArrayList<String>();
        for (var entry : values.entrySet()) {
            var kind = entry.getKey();
            var found = kinds(search(admin, entry.getValue())).contains(kind);
            if (found != held.contains(permissions.get(kind).key())) {
                wrong.add(kind + (found ? " was shown" : " wasn't found"));
            }
        }
        assertThat(wrong).as(role).isEmpty();
    }

    @Test
    void personalValuesMatchOnlyWholeAndResultsAreMasked() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        // Whole, in any case: found, and masked.
        var email = person.email();
        var byEmail = search(owner, email.toUpperCase());
        assertThat(kinds(byEmail)).contains("user");
        assertThat(byEmail.toString()).doesNotContain(email);
        var upi = values.get("upi");
        var byUpi = search(owner, upi);
        assertThat(kinds(byUpi)).contains("upi");
        assertThat(byUpi.toString()).doesNotContain(upi);
        var byPan = search(owner, "ABCPE4321F");
        assertThat(kinds(byPan)).contains("pan");
        assertThat(byPan.toString()).doesNotContain("ABCPE4321F");
        // A part finds nothing: nobody can walk the table a letter at a time.
        assertThat(kinds(search(owner, email.substring(0, email.indexOf('@'))))).doesNotContain("upi", "pan");
        assertThat(kinds(search(owner, email.substring(0, email.length() - 1)))).doesNotContain("user");
        assertThat(kinds(search(owner, upi.substring(0, upi.length() - 1)))).doesNotContain("upi");
        assertThat(kinds(search(owner, "ABCPE4321"))).doesNotContain("pan");
    }

    @Test
    void namesMatchByTrigram() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        jdbc.update(
                "update identity.users set display_name = 'Parameswaran Iyengar' where id = ?",
                person.ids().user());
        var results = search(owner, "Parameswaran Iyengr");
        assertThat(results.get("groups").toString())
                .contains(person.ids().user().toString());
    }

    @Test
    void eachSearchIsInTheAccessLogWithItsTextMasked() throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        search(admin, person.email());
        search(admin, "Parameswaran");
        search(admin, values.get("session"));
        var logged = jdbc.queryForList(
                "select record_id from identity.personal_data_access where admin_id = ? and field = 'search' order by at, id",
                String.class,
                admin.id());
        assertThat(logged).hasSize(3);
        assertThat(logged.get(0)).doesNotContain(person.email()).contains("•••");
        assertThat(logged.get(1)).isEqualTo("Pa•••");
        assertThat(logged.get(2)).isEqualTo(values.get("session"));
    }

    @Test
    void aSingleLetterIsAnsweredWithNothingAndNotLogged() throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        assertThat(search(admin, "a").get("groups")).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.personal_data_access where admin_id = ? and field = 'search'",
                        Integer.class,
                        admin.id()))
                .isZero();
    }
}
