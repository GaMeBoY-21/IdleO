package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/** Safe admin writes (ADR 0051): If-Match on what changes a record, an Idempotency-Key on every POST. */
@ControlPlaneTest
class AdminSafeWritesTests {

    /** Writes to a path with an id that change no record of their own: starting a person's export, ending a session. */
    private static final Set<String> NO_RECORD = Set.of(
            "/admin/v1/users/{id}/exports", "/admin/v1/me/sessions/{id}/end", "/admin/v1/admins/sessions/{id}/end");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(
                        request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"breaking the rules\"}"))
                .andReturn()
                .getResponse();
    }

    private String problem(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).get("type").asString();
    }

    private long version(UUID user) {
        return jdbc.queryForObject("select version from identity.users where id = ?", Long.class, user);
    }

    @Test
    void aWriteToARecordNeedsItsVersionAndAStaleOneIsRefused() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var path = "/admin/v1/users/" + person.id() + "/suspend";

        var none = send(admin.bare(post(path))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(none.getStatus()).isEqualTo(428);
        assertThat(problem(none)).isEqualTo("/problems/version-required");

        var read = version(person.id());
        var stale = send(admin.bare(post(path))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"" + (read + 1) + "\""));
        assertThat(stale.getStatus()).isEqualTo(412);
        assertThat(problem(stale)).isEqualTo("/problems/version-stale");
        assertThat(json.readTree(stale.getContentAsString())
                        .get("currentVersion")
                        .asLong())
                .isEqualTo(read);

        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, person.id()))
                .isEqualTo("active");
        var done = send(admin.bare(post(path))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"" + read + "\""));
        assertThat(done.getStatus()).isEqualTo(200);
        assertThat(json.readTree(done.getContentAsString()).get("version").asLong())
                .isGreaterThan(read);

        // The read before the suspension is now stale.
        var again = send(admin.bare(post("/admin/v1/users/" + person.id() + "/unsuspend"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", String.valueOf(read)));
        assertThat(again.getStatus()).isEqualTo(412);
    }

    @Test
    void everyPostNeedsAnIdempotencyKey() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var refused = send(admin.bare(post("/admin/v1/reconciliation")));
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(problem(refused)).isEqualTo("/problems/idempotency-key-invalid");
    }

    @Test
    void aRetryWithTheSameKeyAnswersAsTheFirstDidEvenThoughItsVersionHasMoved() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var key = UUID.randomUUID().toString();
        var path = "/admin/v1/users/" + person.id() + "/suspend";
        var read = String.valueOf(version(person.id()));
        var first = send(admin.bare(post(path)).header("Idempotency-Key", key).header("If-Match", read));
        var repeat = send(admin.bare(post(path)).header("Idempotency-Key", key).header("If-Match", read));
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(repeat.getStatus()).isEqualTo(200);
        assertThat(repeat.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'user.suspend' and target_id = ?",
                        Integer.class,
                        person.id().toString()))
                .isEqualTo(1);
    }

    @Test
    void aKeyUsedForAnotherRequestIsRefusedAndAFailedRequestGivesItsKeyBack() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var path = "/admin/v1/users/" + person.id() + "/suspend";
        var key = UUID.randomUUID().toString();
        var read = String.valueOf(version(person.id()));

        // Refused for a stale version: the key isn't spent.
        assertThat(send(admin.bare(post(path)).header("Idempotency-Key", key).header("If-Match", "999"))
                        .getStatus())
                .isEqualTo(412);
        assertThat(send(admin.bare(post(path)).header("Idempotency-Key", key).header("If-Match", read))
                        .getStatus())
                .isEqualTo(200);

        var otherPath = "/admin/v1/users/" + UUID.randomUUID() + "/suspend";
        var reused =
                send(admin.bare(post(otherPath)).header("Idempotency-Key", key).header("If-Match", read));
        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(problem(reused)).isEqualTo("/problems/idempotency-key-reused");
    }

    @Test
    void whatIsShownOnceIsNotKeptForARepeat() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var key = UUID.randomUUID().toString();
        var body = "{\"count\":1,\"note\":\"for the safe writes test\"}";
        var first = mvc.perform(admin.bare(post("/admin/v1/invites"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "select response_body from identity.idempotency_keys where idempotency_key = ?",
                        String.class,
                        key))
                .isNull();
        var repeat = mvc.perform(admin.bare(post("/admin/v1/invites"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(repeat.getStatus()).isEqualTo(409);
        assertThat(problem(repeat)).isEqualTo("/problems/secret-shown-once");
    }

    @Test
    void everyWriteToARecordNamesItsRecord() {
        var missing = new ArrayList<String>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            var declared = handler.getMethodAnnotation(RequiresPermission.class);
            if (declared == null || !info.getMethodsCondition().getMethods().contains(RequestMethod.POST)) {
                return;
            }
            for (var path : info.getPatternValues()) {
                if (path.contains("{") && declared.record().isBlank() && !NO_RECORD.contains(path)) {
                    missing.add(path);
                }
            }
        });
        assertThat(missing).isEmpty();
    }
}
