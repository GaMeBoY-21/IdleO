package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.images.internal.TestImages;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kill switches enforced at the API (ADR 0056): each pause refuses exactly what it names with a {@code paused}
 * problem the app shows as a banner, and nothing else. The sessions' and payouts' own are {@code PausedSessionsTests} and
 * {@code PausedPayoutsTests}.
 */
@ControlPlaneTest
class KillSwitchesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void runEverythingAgain() {
        TestFlags.runAll(context);
    }

    private static JsonNode problem(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(503);
        var problem = JSON.readTree(response.getContentAsString());
        assertThat(problem.get("type").asString()).isEqualTo("/problems/paused");
        return problem;
    }

    private MockHttpServletResponse topUp(TestUsers.TestUser renter) throws Exception {
        return mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\": 50000}"))
                .andReturn()
                .getResponse();
    }

    @Test
    @TestFlags.Enforces(Flag.TOPUPS)
    void pausedTopUpsRefuseANewOneAndSayWhenTheyreBack() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var until = Instant.now().plus(Duration.ofMinutes(30));
        TestFlags.pause(context, Flag.TOPUPS, until);

        var refused = topUp(renter);
        var problem = problem(refused);
        assertThat(problem.get("flag").asString()).isEqualTo("topups");
        assertThat(problem.get("detail").asString()).startsWith("Top-ups are paused: we expect to be back by");
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1_700L, 1_800L);
        assertThat(mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the wallet still reads")
                .isEqualTo(200);

        TestFlags.runAll(context);
        assertThat(topUp(renter).getStatus()).isNotEqualTo(503);
    }

    @Test
    @TestFlags.Enforces(Flag.SIGNUPS)
    void pausedSignUpsRefuseSomeoneNewWhileEveryoneElseSignsIn() throws Exception {
        var existing = TestUsers.signUp(mvc);
        TestFlags.pause(context, Flag.SIGNUPS);

        var someoneNew = TestUsers.bearer(
                "new-" + UUID.randomUUID(),
                "new-" + UUID.randomUUID() + "@example.com",
                Instant.now(),
                Duration.ofHours(1));
        var problem = problem(mvc.perform(get("/v1/me").header("Authorization", someoneNew))
                .andReturn()
                .getResponse());
        assertThat(problem.get("detail").asString()).startsWith("Sign-ups are paused: ");
        assertThat(mvc.perform(get("/v1/me").header("Authorization", existing.bearer()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        TestFlags.runAll(context);
        assertThat(mvc.perform(get("/v1/me").header("Authorization", someoneNew))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @TestFlags.Enforces(Flag.HOSTS_LINK)
    void pausedLinkingRefusesANewMachineFromTheAppAndTheWeb() throws Exception {
        var owner = TestUsers.signUp(mvc);
        TestFlags.pause(context, Flag.HOSTS_LINK);

        problem(mvc.perform(post("/v1/host-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineName":"ASUS ROG Strix","os":"windows","agentVersion":"0.1.0",
                                 "gpus":[{"model":"NVIDIA GeForce RTX 4080 Laptop GPU","vramMb":12282}]}
                                """))
                .andReturn()
                .getResponse());
        problem(mvc.perform(post("/v1/hosts")
                        .header("Authorization", owner.bearer())
                        .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"desk rig\"}"))
                .andReturn()
                .getResponse());

        TestFlags.runAll(context);
        assertThat(mvc.perform(post("/v1/hosts")
                                .header("Authorization", owner.bearer())
                                .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"desk rig\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    @TestFlags.Enforces(Flag.IMAGES_CUSTOM)
    void pausedOwnImagesRefuseASubmissionAndSayWhyOnTheRentersPage() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        TestImages.verifyPan(mvc, renter);
        TestFlags.pause(context, Flag.IMAGES_CUSTOM);

        problem(mvc.perform(post("/v1/images")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\": \"docker.io/library/python:3.13-slim\"}"))
                .andReturn()
                .getResponse());
        var page = JSON.readTree(mvc.perform(get("/v1/images").header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(page.toString()).contains("Your own images are paused");
    }
}
