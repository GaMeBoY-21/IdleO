package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Only the web's own origins (the renter app's, the host site's, the website's and the admin site's, ADR 0058) may call
 * the API from a browser, with a bearer token and no cookies (ADR 0018); the console's paths take the admin site's alone.
 */
@ControlPlaneTest
class WebOriginTests {

    static final String WEB = "http://localhost:3000";
    static final String ADMIN = "http://localhost:3004";

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Test
    void theWebOriginMayCallWithABearerTokenAndSeeETagAndLocation() throws Exception {
        mvc.perform(options("/v1/sessions")
                        .header("Origin", WEB)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEB))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/me").header("Origin", WEB).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEB))
                .andExpect(
                        header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("ETag")));
    }

    /** The host site (ADR 0058) is a web origin of its own, with the same access as the renter app's. */
    @Test
    void theHostSiteMayCallToo() throws Exception {
        var host = "http://localhost:3003";
        mvc.perform(options("/v1/hosts")
                        .header("Origin", host)
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", host))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void theAppsPathsDontTakeTheConsolesSessionHeader() throws Exception {
        mvc.perform(options("/v1/sessions")
                        .header("Origin", WEB)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, admin-session"))
                // The answer lists what it allows, and the browser refuses the rest.
                .andExpect(header().string(
                                "Access-Control-Allow-Headers",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsStringIgnoringCase("admin-session"))));
    }

    @Test
    void anyOtherOriginIsRefused() throws Exception {
        mvc.perform(options("/v1/sessions")
                        .header("Origin", "https://elsewhere.example.invalid")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** The admin console is a site of its own (ADR 0058): its origin, and only its, may call /admin/v1. */
    @Test
    void theAdminSiteMayCallTheConsolesPathsWithItsSessionHeaderAndTheAppMayNot() throws Exception {
        mvc.perform(options("/admin/v1/users")
                        .header("Origin", ADMIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, admin-session, idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN))
                .andExpect(header().string(
                                "Access-Control-Allow-Headers",
                                org.hamcrest.Matchers.containsStringIgnoringCase("admin-session")));
        for (var other : java.util.List.of(WEB, "http://localhost:3003", "https://elsewhere.example.invalid")) {
            mvc.perform(options("/admin/v1/users")
                            .header("Origin", other)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    /** The admin site signs in through /v1 (its Terms, its invite code) like the others. */
    @Test
    void theAdminSiteMayCallTheAppsPathsToo() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/me").header("Origin", ADMIN).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN));
    }

    /**
     * Defence in depth beside CORS (ADR 0058): a request to /admin/v1 whose Origin is any but the admin site's is
     * refused by the admin chain itself, as a path that doesn't exist. Here the Origin is the API's own, which CORS
     * doesn't look at; a request with no Origin, from an operator's tool, goes on to the admin checks.
     */
    @Test
    void theConsolesPathsRefuseAnyOriginButTheAdminSitesWhateverCorsSays() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(get("/admin/v1/audit")).header("Origin", "http://localhost"))
                .andExpect(status().isNotFound());
        mvc.perform(admin.as(get("/admin/v1/audit")).header("Origin", ADMIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN));
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isOk());
        mvc.perform(admin.as(get("/admin/v1/audit")).header("Origin", WEB)).andExpect(status().isForbidden());
    }

    @Test
    void theAdminConsoleCanReadAFilesNameButTheAppCant() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/admin/v1/session").header("Origin", ADMIN).header("Authorization", user.bearer()))
                .andExpect(header().string(
                                "Access-Control-Expose-Headers",
                                org.hamcrest.Matchers.containsStringIgnoringCase("Content-Disposition")));
        mvc.perform(get("/v1/me").header("Origin", WEB).header("Authorization", user.bearer()))
                .andExpect(header().string(
                                "Access-Control-Expose-Headers",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsStringIgnoringCase("Content-Disposition"))));
    }
}
