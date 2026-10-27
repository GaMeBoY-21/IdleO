package com.idleo.controlplane.images.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Renters' own images are switched off with {@code images.enabled=false}, production's default (ADR 0042): nobody can
 * check or book one, whatever their PAN, the list says why, and the template that runs one isn't offered.
 */
@ControlPlaneTest
@TestPropertySource(properties = {"images.enabled=false", "images.scanner.port=18096"})
class ImagesDisabledTests {

    @Autowired
    MockMvc mvc;

    @Test
    void theListSaysOwnImagesAreNotAvailableYetAndNoOneCanUseThem() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        mvc.perform(get("/v1/images").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.canUse").value(false))
                .andExpect(jsonPath("$.whyNot").value("Your own images aren't available yet."));
    }

    @Test
    void anImageCantBeSubmitted() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        mvc.perform(post("/v1/images")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"pytorch/pytorch:2.5.1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"))
                .andExpect(jsonPath("$.detail").value("Your own images aren't available yet."));
    }

    @Test
    void theTemplateThatRunsAnImageIsNotOffered() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        mvc.perform(get("/v1/templates").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.id == 'custom-image')]").isEmpty())
                .andExpect(jsonPath("$.templates[*].ownImage", Matchers.not(Matchers.hasItem(true))));
    }
}
