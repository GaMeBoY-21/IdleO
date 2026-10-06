package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Fake users for tests in any package: each signs in once, which creates them. */
public final class TestUsers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestUsers() {}

    /** A signed-in fake user: their bearer header and our user id. */
    public record TestUser(String bearer, UUID id) {}

    public static TestUser signUp(MockMvc mvc) throws Exception {
        var bearer = "Bearer " + TestIssuers.get().validToken();
        var body = mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new TestUser(
                bearer, UUID.fromString(JSON.readTree(body).get("id").asString()));
    }
}
