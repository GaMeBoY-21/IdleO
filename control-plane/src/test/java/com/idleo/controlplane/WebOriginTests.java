package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Only the web app's own origins (the renter app's and the host site's, ADR 0058) may call the API from a browser, with
 * a bearer token and no cookies (ADR 0018).
 */
@ControlPlaneTest
class WebOriginTests {

    static final String WEB = "http://localhost:3000";

    @Autowired
    MockMvc mvc;

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

    @Test
    void theAdminConsoleMayCallFromTheWebOriginWithItsSessionHeader() throws Exception {
        mvc.perform(options("/admin/v1/users")
                        .header("Origin", WEB)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, admin-session, idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEB))
                .andExpect(header().string(
                                "Access-Control-Allow-Headers",
                                org.hamcrest.Matchers.containsStringIgnoringCase("admin-session")));
        mvc.perform(options("/admin/v1/users")
                        .header("Origin", "https://elsewhere.example.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAdminConsoleCanReadAFilesNameButTheAppCant() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/admin/v1/session").header("Origin", WEB).header("Authorization", user.bearer()))
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
