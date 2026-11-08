package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.TestAdmins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who can do what in the console (ADR 0051): every admin endpoint times every role, worked out from the declarations
 * and the roles in the database, is the committed table {@code admin-permission-matrix.csv}, so a change in who can do
 * what shows up in review; and each cell is true of a real request. After an intended change, run {@code ./mvnw test
 * -Dtest=AdminPermissionMatrixTests -Dmatrix.write=true} and review the diff.
 */
@ControlPlaneTest
class AdminPermissionMatrixTests {

    private static final List<String> ROLES =
            List.of("owner", "operations", "support", "trust_safety", "finance", "auditor");
    private static final Path TABLE = Path.of("src/test/resources/admin-permission-matrix.csv");

    /** Ends the very session the test goes on using; the table still holds it. */
    private static final Set<String> NOT_CALLED = Set.of("DELETE /admin/v1/session");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    AdminSafeWrites safeWrites;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private record Endpoint(String method, String path, AdminPermission permission) {
        String name() {
            return method + " " + path;
        }
    }

    private List<Endpoint> endpoints() {
        var endpoints = new ArrayList<Endpoint>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            var declared = handler.getMethodAnnotation(RequiresPermission.class);
            if (declared == null) {
                return;
            }
            for (var path : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(method.name(), path, declared.value()));
                }
            }
        });
        endpoints.sort(Comparator.comparing(Endpoint::name));
        return endpoints;
    }

    private Set<String> permissionsOf(String role) {
        return Set.copyOf(jdbc.queryForList(
                "select permission from identity.admin_role_permissions where role_id = ?", String.class, role));
    }

    private boolean allowed(Set<String> held, Endpoint endpoint) {
        return endpoint.permission() == AdminPermission.SELF
                || held.contains(endpoint.permission().key());
    }

    @Test
    void theTableOfWhoCanDoWhatIsTheCommittedOne() throws IOException {
        var held = new LinkedHashMap<String, Set<String>>();
        ROLES.forEach(role -> held.put(role, permissionsOf(role)));
        var table = new StringBuilder("endpoint,permission," + String.join(",", ROLES) + "\n");
        for (var endpoint : endpoints()) {
            table.append(endpoint.name())
                    .append(',')
                    .append(endpoint.permission().key());
            for (var role : ROLES) {
                table.append(',').append(allowed(held.get(role), endpoint) ? "allow" : "403");
            }
            table.append('\n');
        }
        if (Boolean.getBoolean("matrix.write")) {
            Files.writeString(TABLE, table);
        }
        assertThat(table.toString())
                .as("the permission matrix; after an intended change run with -Dmatrix.write=true and review the diff")
                .isEqualTo(Files.readString(TABLE));
    }

    @Test
    void everyCellOfTheTableIsTrueOfARealRequest() throws Exception {
        for (var role : ROLES) {
            var admin = TestAdmins.signInAs(mvc, jdbc, clock, role);
            var held = permissionsOf(role);
            for (var endpoint : endpoints()) {
                if (NOT_CALLED.contains(endpoint.name())) {
                    continue;
                }
                // A made-up record and an empty body: an allowed POST stops at validation, so nothing happens.
                var path = endpoint.path()
                        .replace("{minuteIndex}", "1")
                        .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                var response = mvc.perform(admin.as(request(HttpMethod.valueOf(endpoint.method()), path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")))
                        .andReturn()
                        .getResponse();
                var refused = response.getStatus() == 403
                        && json.readTree(response.getContentAsString())
                                .path("type")
                                .asString()
                                .endsWith("/admin-permission-required");
                assertThat(refused)
                        .as("%s as %s (answered %d)", endpoint.name(), role, response.getStatus())
                        .isEqualTo(!allowed(held, endpoint));
                assertThat(response.getStatus())
                        .as("%s as %s", endpoint.name(), role)
                        .isLessThan(500);
            }
        }
    }

    @Test
    void refusalNamesTheMissingPermission() throws Exception {
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var answer = mvc.perform(support.as(request(HttpMethod.GET, "/admin/v1/audit")))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).isEqualTo(403);
        var problem = json.readTree(answer.getContentAsString());
        assertThat(problem.get("type").asString()).isEqualTo("/problems/admin-permission-required");
        assertThat(problem.get("permission").asString()).isEqualTo("audit.read");
    }

    @Test
    void anAdminWithNoRoleCanSignInAndSeeNothingElse() throws Exception {
        var none = TestAdmins.signInAs(mvc, jdbc, clock);
        var status = json.readTree(mvc.perform(none.as(request(HttpMethod.GET, "/admin/v1/session")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(status.get("active").asBoolean()).isTrue();
        assertThat(status.get("permissions").isEmpty()).isTrue();
        assertThat(mvc.perform(none.as(request(HttpMethod.GET, "/admin/v1/control-room")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void theOwnerHoldsEveryPermissionAndRolesNameOnlyKnownOnes() {
        var known = Arrays.stream(AdminPermission.values())
                .filter(permission -> permission != AdminPermission.SELF)
                .map(AdminPermission::key)
                .collect(Collectors.toSet());
        assertThat(permissionsOf("owner")).containsExactlyInAnyOrderElementsOf(known);
        assertThat(jdbc.queryForList("select distinct permission from identity.admin_role_permissions", String.class))
                .allSatisfy(permission -> assertThat(known).contains(permission));
    }

    @Test
    void theAuditorOnlyReads() {
        assertThat(permissionsOf("auditor"))
                .allSatisfy(permission -> assertThat(permission).endsWith(".read"));
        assertThat(permissionsOf("auditor")).contains("audit.read");
    }

    /** A controller as someone might write it, forgetting the permission. */
    static class Forgetful {
        @GetMapping("/admin/v1/forgotten")
        void forgotten() {}
    }

    @Test
    void anEndpointThatDeclaresNoPermissionFailsStartup() throws Exception {
        var handler = new HandlerMethod(new Forgetful(), Forgetful.class.getDeclaredMethod("forgotten"));
        Map<RequestMappingInfo, HandlerMethod> handlers =
                Map.of(RequestMappingInfo.paths("/admin/v1/forgotten").build(), handler);
        var mapping = new RequestMappingHandlerMapping() {
            @Override
            public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
                return handlers;
            }
        };
        assertThatThrownBy(() -> new AdminEndpointCheck(mapping, safeWrites).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/admin/v1/forgotten");
    }

    @Test
    void everyEndpointOfTheRealApplicationDeclaresOne() {
        assertThat(AdminEndpointCheck.undeclared(mappings.getHandlerMethods())).isEmpty();
    }
}
