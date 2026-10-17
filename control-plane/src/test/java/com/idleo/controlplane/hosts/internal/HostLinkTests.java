package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestUsers;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Linking a host by approval (ADR 0032): the agent asks, its owner approves or denies in the web app, and the agent's
 * poll gets the one-time enrolment code exactly once; every way it can end, and what is stored.
 */
@ControlPlaneTest
class HostLinkTests {

    private static final String MACHINE = """
            {"machineName":"ASUS ROG Strix","os":"windows","agentVersion":"0.1.0",
             "gpus":[{"model":"NVIDIA GeForce RTX 4080 Laptop GPU","vramMb":12282}]}
            """;
    private static final String TERMS = """
            {"name":"Nikki's laptop","batchPricePaisePerMin":18}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private final JsonMapper json = JsonMapper.builder().build();
    private TestUsers.TestUser owner;

    @BeforeEach
    void signIn() throws Exception {
        owner = TestUsers.signUpLasting(mvc, Duration.ofHours(2));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode start() throws Exception {
        return json.readTree(mvc.perform(post("/v1/host-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MACHINE))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private ResultActions ticket(JsonNode link) throws Exception {
        return ticket(link.get("id").asString(), link.get("pollToken").asString());
    }

    private ResultActions ticket(String id, String token) throws Exception {
        return mvc.perform(post("/v1/host-links/" + id + "/ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pollToken\":\"" + token + "\"}"));
    }

    private ResultActions approve(JsonNode link, TestUsers.TestUser who, String terms) throws Exception {
        return mvc.perform(post("/v1/host-links/" + link.get("id").asString() + "/approve")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(terms));
    }

    private int hostsOf(TestUsers.TestUser who) {
        return jdbc.queryForObject("select count(*) from hosts.hosts where owner_user_id = ?", Integer.class, who.id());
    }

    private int audits(String action, String linkId) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                linkId);
    }

    @Test
    void askingNeedsNoSignInAndKeepsOnlyAHashOfThePollToken() throws Exception {
        var link = start();

        assertThat(link.get("approveUrl").asString())
                .isEqualTo("http://localhost:3000/link/" + link.get("id").asString() + "/");
        assertThat(link.get("intervalSeconds").asLong()).isEqualTo(3);
        assertThat(link.get("expiresInSeconds").asLong()).isEqualTo(600);
        var row = jdbc.queryForObject(
                "select t::text from hosts.host_links t where id = ?::uuid",
                String.class,
                link.get("id").asString());
        assertThat(row)
                .contains("ASUS ROG Strix")
                .doesNotContain(link.get("pollToken").asString());
    }

    @Test
    void theAgentIsToldToWaitUntilItsOwnerApprovesThenGetsItsCodeOnceAndEnrolsWithIt() throws Exception {
        var link = start();
        ticket(link)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/authorization-pending"));

        // The approval page reads what the machine said, and never the token.
        var seen = mvc.perform(
                        get("/v1/host-links/" + link.get("id").asString()).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineName").value("ASUS ROG Strix"))
                .andExpect(jsonPath("$.gpus[0].vramMb").value(12282))
                .andExpect(jsonPath("$.state").value("pending"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(seen).doesNotContain(link.get("pollToken").asString()).doesNotContain("pollToken");

        approve(link, owner, TERMS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approved"))
                .andExpect(jsonPath("$.awaitsActivation").value(true));
        var hostId = jdbc.queryForObject(
                "select id::text from hosts.hosts where owner_user_id = ?", String.class, owner.id());
        // The host is the owner's, named as they confirmed, priced as they chose, and still pending (the pilot rule).
        assertThat(jdbc.queryForMap(
                        "select h.name, h.status, h.gpu_temp_cap_c, p.price_paise_per_min from hosts.hosts h"
                                + " join hosts.price_listings p on p.host_id = h.id where h.id = ?::uuid",
                        hostId))
                .containsEntry("name", "Nikki's laptop")
                .containsEntry("status", "pending")
                .containsEntry("gpu_temp_cap_c", 85)
                .containsEntry("price_paise_per_min", 18);

        var ticket = json.readTree(ticket(link)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostId").value(hostId))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(ticket.get("enrolmentCode").asString()).matches("([0-9A-Z]{5}-){3}[0-9A-Z]{5}");
        assertThat(ticket.get("ownerHint").asString()).hasSize(1);

        // Exactly once: a second poll, or an approval after it, never makes a second code or host.
        ticket(link)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-expired"));
        assertThat(hostsOf(owner)).isEqualTo(1);

        // The code is the one the existing enrolment takes, and it works once.
        TestAgent.enrolWithCode(grpcPort, ticket.get("enrolmentCode").asString());
        assertThatThrownBy(() -> TestAgent.enrolWithCode(
                        grpcPort, ticket.get("enrolmentCode").asString()))
                .isInstanceOf(StatusRuntimeException.class);
        assertThat(audits("host_link.approve", link.get("id").asString())).isEqualTo(1);
    }

    @Test
    void approvingTwiceReturnsTheSameHostAndMakesOne() throws Exception {
        var link = start();
        var first = json.readTree(approve(link, owner, TERMS)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var again = json.readTree(approve(link, owner, TERMS)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(again.get("hostId").asString()).isEqualTo(first.get("hostId").asString());
        assertThat(hostsOf(owner)).isEqualTo(1);
        assertThat(audits("host_link.approve", link.get("id").asString())).isEqualTo(1);
    }

    @Test
    void anotherPersonCannotApproveALinkThatWasApprovedAndItIsNotTheirs() throws Exception {
        var link = start();
        approve(link, owner, TERMS).andExpect(status().isOk());
        var other = TestUsers.signUp(mvc);

        approve(link, other, TERMS)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        assertThat(hostsOf(other)).isZero();
    }

    @Test
    void denyingEndsTheLinkAndTheAgentIsToldSo() throws Exception {
        var link = start();
        mvc.perform(post("/v1/host-links/" + link.get("id").asString() + "/deny")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("denied"));

        ticket(link)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-denied"));
        approve(link, owner, TERMS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-denied"));
        assertThat(hostsOf(owner)).isZero();
        assertThat(audits("host_link.deny", link.get("id").asString())).isEqualTo(1);
    }

    @Test
    void anApprovedLinkCannotBeDenied() throws Exception {
        var link = start();
        approve(link, owner, TERMS).andExpect(status().isOk());

        mvc.perform(post("/v1/host-links/" + link.get("id").asString() + "/deny")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/host-link-approved"));
    }

    @Test
    void aLinkExpiresAfterTenMinutesWhateverIsDoneWithIt() throws Exception {
        var pending = start();
        var approved = start();
        approve(approved, owner, TERMS).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(11));

        mvc.perform(get("/v1/host-links/" + pending.get("id").asString()).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.state").value("expired"));
        approve(pending, owner, TERMS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-expired"));
        ticket(pending)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-expired"));
        // Even an approved link's ticket must be fetched within the ten minutes.
        ticket(approved)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/host-link-expired"));
    }

    @Test
    void aWrongPollTokenOrUnknownLinkIsNotFound() throws Exception {
        var link = start();
        ticket(link.get("id").asString(), "not-the-token").andExpect(status().isNotFound());
        ticket(UUID.randomUUID().toString(), link.get("pollToken").asString()).andExpect(status().isNotFound());
    }

    @Test
    void anAnonymousCallerCannotReadApproveOrDeny() throws Exception {
        var link = start();
        var id = link.get("id").asString();
        mvc.perform(get("/v1/host-links/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/host-links/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TERMS))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/host-links/" + id + "/deny")).andExpect(status().isUnauthorized());
    }

    @Test
    void anApiKeyCannotApprove() throws Exception {
        var link = start();
        var key = json.readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", owner.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"ci\",\"scope\":\"full\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();

        mvc.perform(post("/v1/host-links/" + link.get("id").asString() + "/approve")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TERMS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
        assertThat(hostsOf(owner)).isZero();
    }

    @Test
    void whatTheMachineSaysIsBoundedAndNeverMoreThanEightGpus() throws Exception {
        mvc.perform(post("/v1/host-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineName\":\"\",\"os\":\"windows\",\"agentVersion\":\"0.1.0\",\"gpus\":[]}"))
                .andExpect(status().isBadRequest());
        var nine = "{\"model\":\"RTX\",\"vramMb\":8000},".repeat(8) + "{\"model\":\"RTX\",\"vramMb\":8000}";
        mvc.perform(post("/v1/host-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineName\":\"x\",\"os\":\"windows\",\"agentVersion\":\"0.1.0\",\"gpus\":["
                                + nine + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anApprovedHostIsAlwaysCreatedPendingWhileAutoActivateIsOff() throws Exception {
        var link = start();
        approve(link, owner, TERMS).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select status from hosts.hosts where owner_user_id = ?", String.class, owner.id()))
                .isEqualTo("pending");
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.status_changes s join hosts.hosts h on h.id = s.host_id"
                                + " where h.owner_user_id = ?",
                        Integer.class,
                        owner.id()))
                .isZero();
    }
}
