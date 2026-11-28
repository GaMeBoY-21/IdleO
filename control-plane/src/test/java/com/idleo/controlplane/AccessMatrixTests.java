package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may call what, for every operation in {@code api/openapi.yaml}, generated from the contract so a new endpoint is
 * covered the day it's added (ADR 0025, ADR 0027):
 *
 * <ul>
 *   <li>every {@code /v1} call that isn't one of the identity module's signed-out calls answers 401 to no token;
 *   <li>every {@code /admin/v1} call answers 404, exactly like a page that doesn't exist, to no token, to a person who
 *       isn't an admin and to that person's API key (the roles themselves: AdminPermissionMatrixTests);
 *   <li>every {@code /v1} call that names a thing in its path answers another person 404 for the first person's thing,
 *       and never shows any of it. Each such path is matched to the table that says whose the thing is
 *       ({@link #OWNED}); a path that matches none, and isn't one of {@link #OPEN_BY_ID}, fails the test until it's
 *       given one.
 * </ul>
 */
@ControlPlaneTest
class AccessMatrixTests {

    private static final Path CONTRACT = Path.of("..", "api", "openapi.yaml");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Whose a path's thing is: the path's first segments, and the query that finds one of the person's ({@code ?} is
     * their id). Ordered: the first match wins.
     */
    private static final Map<Pattern, String> OWNED = new LinkedHashMap<>();

    static {
        owned("/v1/api-keys/", "select id from identity.api_keys where user_id = ?");
        owned("/v1/disputes/", "select id from trust.disputes where renter_id = ?");
        owned("/v1/hosts/", "select id from hosts.hosts where owner_user_id = ? and removed_at is null");
        owned("/v1/images/", "select id from images.images where user_id = ?");
        owned("/v1/me/enforcements/", "select id from trust.enforcements where user_id = ?");
        owned("/v1/me/exports/", "select id from privacy.exports where user_id = ?");
        owned("/v1/payout-details/upi-checks/", "select id from payouts.methods where user_id = ?");
        owned("/v1/sessions/", "select id from sessions.sessions where renter_id = ?");
        owned("/v1/support/tickets/", "select id from support.tickets where user_id = ?");
        owned("/v1/uploads/", "select id from sessions.uploads where user_id = ?");
        owned("/v1/wallet/invoices/", "select id from billing.invoices where user_id = ?");
        owned("/v1/wallet/topups/", "select id from billing.topups where user_id = ?");
    }

    private static void owned(String prefix, String query) {
        OWNED.put(Pattern.compile("^" + Pattern.quote(prefix) + "\\{[^}]+}"), query);
    }

    /**
     * The calls that act on a thing by its id for whoever holds the id, by design, and why. Each id is a secret handed
     * to one device, and acting on it only ever binds the caller's own account.
     */
    private static final Map<String, String> OPEN_BY_ID = Map.of(
            "POST /v1/cli-logins/{id}/approve",
            "the device sign-in (ADR 0025): approving gives the approver's own key to the device that showed the code",
            "GET /v1/host-links/{id}",
            "linking by approval (ADR 0032): the id is in the link the agent opens; reading it shows only the machine",
            "POST /v1/host-links/{id}/approve",
            "linking by approval (ADR 0032): approving makes the machine the approver's own",
            "POST /v1/host-links/{id}/deny",
            "linking by approval (ADR 0032): denying only ends the request");

    /** Bodies that pass validation, so a write reaches its owner check rather than stopping at a 400. */
    private static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("PATCH /v1/hosts/{id}", "{\"name\":\"not yours\"}"),
            Map.entry(
                    "PUT /v1/hosts/{id}/listing",
                    "{\"batchPricePaisePerMin\":12,\"gpuTempCapC\":85,\"maxConcurrentSessions\":1}"),
            Map.entry("POST /v1/sessions/{id}/rating", "{\"stars\":5}"),
            Map.entry("POST /v1/sessions/{id}/stop", "{\"checkpointFirst\":false}"),
            Map.entry("POST /v1/support/tickets/{id}/messages", "{\"message\":\"not yours\"}"),
            Map.entry("POST /v1/uploads/{id}/part-urls", "{\"path\":\"a.txt\",\"partNumbers\":[1]}"),
            Map.entry(
                    "POST /v1/uploads/{id}/complete",
                    "{\"files\":[{\"path\":\"a.txt\",\"parts\":[{\"number\":1,\"etag\":\"x\"}]}]}"),
            Map.entry("POST /v1/me/enforcements/{id}/appeal", "{\"body\":\"not yours\"}"),
            Map.entry(
                    "POST /v1/wallet/topups/{id}/confirm",
                    "{\"razorpayPaymentId\":\"pay_x\",\"razorpayOrderId\":\"order_x\",\"razorpaySignature\":\"x\"}"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Autowired
    MutableClock clock;

    @Autowired
    @Qualifier("signedOutCalls")
    RequestMatcher signedOutCalls;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    record Operation(String method, String path) {

        @Override
        public String toString() {
            return method + " " + path;
        }

        boolean admin() {
            return path.startsWith("/admin/");
        }

        /** The path with every variable filled with {@code id}. */
        String with(String id) {
            return path.replaceAll("\\{[^}]+}", id);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Operation> operations() throws Exception {
        Map<String, Object> contract = new Yaml().load(Files.readString(CONTRACT));
        var operations = new ArrayList<Operation>();
        ((Map<String, Map<String, Object>>) contract.get("paths"))
                .forEach((path, item) -> item.keySet().stream()
                        .filter(method ->
                                List.of("get", "post", "put", "patch", "delete").contains(method))
                        .forEach(method ->
                                operations.add(new Operation(method.toUpperCase(java.util.Locale.ROOT), path))));
        return operations;
    }

    private boolean signedOut(Operation operation) {
        var probe = new MockHttpServletRequest(
                operation.method(), operation.with(UUID.randomUUID().toString()));
        probe.setServletPath(probe.getRequestURI());
        return signedOutCalls.matches(probe);
    }

    private MockHttpServletRequestBuilder call(Operation operation, String path) {
        var builder = request(HttpMethod.valueOf(operation.method()), URI.create(path))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (!operation.method().equals("GET") && !operation.method().equals("DELETE")) {
            builder.contentType(MediaType.APPLICATION_JSON).content(BODIES.getOrDefault(operation.toString(), "{}"));
        }
        return builder;
    }

    /** An answer, without the one thing that differs between any two: the id it was for. */
    record Answer(int status, String body) {

        @Override
        public String toString() {
            return status + " " + body.substring(0, Math.min(160, body.length()));
        }
    }

    private Answer answer(Operation operation, String id, TestUsers.TestUser caller) throws Exception {
        var response = mvc.perform(call(operation, operation.with(id)).header("Authorization", caller.bearer()))
                .andReturn()
                .getResponse();
        var body = response.getContentAsString().replace(id, "{id}");
        var json =
                response.getContentType() != null && response.getContentType().contains("json") && body.startsWith("{");
        return new Answer(
                response.getStatus(),
                json
                        ? ((tools.jackson.databind.node.ObjectNode) JSON.readTree(body))
                                .without("instance")
                                .toString()
                        : body);
    }

    @Test
    void everyCallButTheSignedOutOnesNeedsASignIn() throws Exception {
        var wrong = new ArrayList<String>();
        for (var operation : operations()) {
            if (operation.admin() || signedOut(operation)) {
                continue;
            }
            int status = mvc.perform(
                            call(operation, operation.with(UUID.randomUUID().toString())))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status != 401) {
                wrong.add(operation + " answered " + status);
            }
        }
        assertThat(wrong).as("calls that answer without a sign-in").isEmpty();
    }

    @Test
    void noAdminCallAnswersAnyoneButAnAdmin() throws Exception {
        var person = TestUsers.signUp(mvc);
        var key = JSON.readTree(mvc.perform(call(new Operation("POST", "/v1/api-keys"), "/v1/api-keys")
                                .header("Authorization", person.bearer())
                                .content("{\"name\":\"matrix\",\"scope\":\"full\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
        var wrong = new ArrayList<String>();
        for (var operation : operations()) {
            if (!operation.admin()) {
                continue;
            }
            var path = operation.with(UUID.randomUUID().toString());
            for (var who : new String[] {null, person.bearer(), "Bearer " + key}) {
                var request = call(operation, path);
                if (who != null) {
                    request.header("Authorization", who);
                }
                int status = mvc.perform(request).andReturn().getResponse().getStatus();
                if (status != 404) {
                    wrong.add(operation + " answered " + status + " to "
                            + (who == null ? "no token" : who.startsWith("Bearer idleo_") ? "a key" : "a non-admin"));
                }
            }
        }
        assertThat(wrong)
                .as("admin calls that answer someone who isn't an admin")
                .isEmpty();
    }

    @Test
    void noOneReachesAnotherPersonsThings() throws Exception {
        var owner = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "matrix-owner-" + UUID.randomUUID().toString().substring(0, 8));
        // The one thing People.make doesn't make: a copy of their data, asked for as the person would.
        mvc.perform(call(new Operation("POST", "/v1/me/exports"), "/v1/me/exports")
                .header("Authorization", owner.signedInAt(clock.instant())));
        // Ready, as its job would leave it, so its download is there for its owner.
        jdbc.update(
                "update privacy.exports set state = 'ready', ready_at = now(), expires_at = now() + interval '1 day',"
                        + " size_bytes = 1 where user_id = ?",
                owner.user().id());
        // A machine that's theirs and not removed (People.make's is removed, so an erasure can run).
        jdbc.update(
                "insert into hosts.hosts (id, owner_user_id, name, status, created_at, updated_at, version, os,"
                        + " agent_version) values (?, ?, ?, 'active', now(), now(), 0, 'windows', '0.1.0')",
                UUID.randomUUID(),
                owner.user().id(),
                owner.marker() + "'s laptop");
        var other = TestUsers.signUp(mvc);
        // Each kind's ids, read once before anyone calls anything, so a delete can't hide what comes after it.
        var found = new java.util.HashMap<String, List<UUID>>();
        OWNED.values()
                .forEach(sql -> found.put(
                        sql, jdbc.queryForList(sql, UUID.class, owner.user().id())));
        var unclaimed = new ArrayList<String>();
        var wrong = new ArrayList<String>();
        for (var operation : operations()) {
            if (operation.admin() || !operation.path().contains("{") || signedOut(operation)) {
                continue;
            }
            if (OPEN_BY_ID.containsKey(operation.toString())) {
                continue;
            }
            var query = OWNED.entrySet().stream()
                    .filter(entry -> entry.getKey().matcher(operation.path()).find())
                    .map(Map.Entry::getValue)
                    .findFirst();
            if (query.isEmpty()) {
                unclaimed.add(operation.toString());
                continue;
            }
            var ids = found.computeIfAbsent(
                    query.get(),
                    sql -> jdbc.queryForList(sql, UUID.class, owner.user().id()));
            if (ids.isEmpty()) {
                unclaimed.add(operation + " (People.make made nothing of its kind)");
                continue;
            }
            // The id is real: its owner reads it (a stream or a download only as far as its status).
            if (operation.method().equals("GET")) {
                int own = mvc.perform(
                                call(operation, operation.with(ids.getFirst().toString()))
                                        .header("Authorization", owner.user().bearer()))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (own / 100 != 2 && own != 409) {
                    wrong.add(operation + " answered its owner " + own + ", so the 404 below proves nothing");
                }
            }
            var theirs = answer(operation, ids.getFirst().toString(), other);
            var nobodys = answer(operation, UUID.randomUUID().toString(), other);
            // The same answer as for a thing that doesn't exist, given past validation, and nothing of the owner's.
            if (!theirs.equals(nobodys)
                    || theirs.status() < 401
                    || theirs.body().contains(owner.marker())
                    || theirs.body().contains(owner.email())) {
                wrong.add(operation + " answered " + theirs + " where a thing that doesn't exist answers " + nobodys);
            }
        }
        // What another person reached first: a delete that went through leaves later calls nothing to find.
        assertThat(wrong)
                .as("calls that let another person reach the owner's things")
                .isEmpty();
        assertThat(unclaimed)
                .as("calls on a thing by id that no rule says whose it is")
                .isEmpty();
    }
}
