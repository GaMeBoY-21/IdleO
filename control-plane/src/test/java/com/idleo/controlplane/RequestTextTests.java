package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Text the API never takes (RequestText): a NUL, which PostgreSQL can't store, and half of a UTF-16 pair, which can't
 * be written as UTF-8 at all. Either in a JSON body is a 400 that says the request can't be read, never a 500 or a
 * value quietly changed. A query Tomcat can't parse: MalformedQueryTests.
 */
@ControlPlaneTest
class RequestTextTests {

    @Autowired
    MockMvc mvc;

    private void refused(String body) throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/malformed-request"));
    }

    @Test
    void aNulInAJsonStringIsRefused() throws Exception {
        refused("{\"name\":\"a\\u0000b\",\"scope\":\"full\"}");
    }

    @Test
    void halfASurrogatePairInAJsonStringIsRefused() throws Exception {
        refused("{\"name\":\"j\\ud956\",\"scope\":\"full\"}");
        refused("{\"name\":\"j\\udc29x\",\"scope\":\"full\"}");
    }

    @Test
    void aWholePairIsText() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"laptop \\ud83d\\ude00\",\"scope\":\"full\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("laptop 😀"));
    }
}
