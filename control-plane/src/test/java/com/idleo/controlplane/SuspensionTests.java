package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@ControlPlaneTest
class SuspensionTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void suspendedUserGets403WhateverTheirTokenSays() throws Exception {
        var issuers = TestIssuers.get();
        var subject = UUID.randomUUID().toString();
        var token = issuers.token(claims -> claims.subject(subject));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // No endpoint suspends users yet, so the test sets the status directly.
        jdbc.update("""
                update identity.users set status = 'suspended'
                where id = (select user_id from identity.external_identities where issuer = ? and subject = ?)
                """, issuers.issuerA, subject);

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("This account is suspended."));
    }
}
