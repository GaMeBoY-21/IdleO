package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.OfferDeclineReason;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an owner does with a machine (ADR 0023): pause and resume, the disk kept free for them, rename, remove. Each is
 * checked against what the scheduler does, through the quote, which uses the same filters as booking.
 */
@ControlPlaneTest
class HostLifecycleTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private final List<ConnectedHost> hosts = new ArrayList<>();

    @AfterEach
    void tidy() {
        hosts.forEach(ConnectedHost::disconnect);
        hosts.clear();
    }

    private ConnectedHost host() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        hosts.add(host);
        return host;
    }

    private ResultActions act(ConnectedHost host, String action, String body) throws Exception {
        return mvc.perform(post("/v1/hosts/" + host.id() + "/" + action)
                .header("Authorization", host.agent().bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode hostView(ConnectedHost host) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** How many hosts the scheduler says fit a smoke test now, which is what an offer would find. */
    private int fitNow() throws Exception {
        var renter = TestUsers.signUp(mvc);
        return JSON.readTree(mvc.perform(post("/v1/session-quotes")
                                .header("Authorization", renter.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5,
                                         "maxSpendPaise": 10000}
                                        """))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("hostsFitNow")
                .asInt();
    }

    private void awaitFit(int wanted) throws Exception {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (fitNow() != wanted && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(fitNow()).isEqualTo(wanted);
    }

    @Test
    void aPausedHostIsNeverOfferedASessionAndResumingBringsItBack() throws Exception {
        var host = host();
        awaitFit(1);

        act(host, "pause", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pausedUntil").doesNotExist());
        assertThat(fitNow()).isZero();
        assertThat(hostView(host).get("paused").asBoolean()).isTrue();

        act(host, "resume", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
        awaitFit(1);
    }

    @Test
    void aPauseWithAnEndEndsByItself() throws Exception {
        var host = host();
        var until = Instant.now().plus(Duration.ofHours(2));

        act(host, "pause", "{\"until\": \"%s\"}".formatted(until))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pausedUntil").exists());
        assertThat(fitNow()).isZero();

        // Time passes: the pause's end is now in the past, and nothing else changes.
        jdbc.update("update hosts.hosts set paused_until = now() - interval '1 minute' where id = ?", host.id());
        assertThat(hostView(host).get("paused").asBoolean()).isFalse();
        assertThat(hostView(host).get("pausedUntil").isNull()).isTrue();
        awaitFit(1);
    }

    @Test
    void aPauseMustEndInTheFutureWithinSevenDays() throws Exception {
        var host = host();

        act(host, "pause", "{\"until\": \"%s\"}".formatted(Instant.now().plus(Duration.ofDays(8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/pause-too-long"));
        act(host, "pause", "{\"until\": \"%s\"}".formatted(Instant.now().minusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        assertThat(hostView(host).get("paused").asBoolean()).isFalse();
    }

    @Test
    void aRunningSessionFinishesWhenItsHostIsPaused() throws Exception {
        var host = host();
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        var booked = JSON.readTree(mvc.perform(post("/v1/sessions")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5,
                                         "maxSpendPaise": 10000}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var offer = host.awaitOffer(WAIT);
        host.reply(offer.getOfferId(), true, OfferDeclineReason.OFFER_DECLINE_REASON_UNSPECIFIED);
        host.awaitStart(WAIT);

        act(host, "pause", "{}").andExpect(status().isOk());

        var session = JSON.readTree(mvc.perform(get("/v1/sessions/" + booked).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(session.get("state").asString()).isEqualTo("PROVISIONING");
        mvc.perform(post("/v1/sessions/" + booked + "/cancel").header("Authorization", renter.bearer()));
    }

    @Test
    void theDiskAnOwnerKeepsFreeComesOffWhatASessionMayUse() throws Exception {
        var host = host();
        // cuda-smoke-test needs 1,127 MB with its margin. Keeping 10 GB free, a host needs 1,127 + 10,240 MB.
        mvc.perform(put("/v1/hosts/" + host.id() + "/listing")
                        .header("Authorization", host.agent().bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1,
                                 "keepFreeGb": 10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keepFreeGb").value(10));

        host.freeDiskMb().set(1_126 + 10 * 1024);
        Thread.sleep(1_200);
        assertThat(fitNow()).isZero();
        host.freeDiskMb().set(1_127 + 10 * 1024);
        awaitFit(1);
        assertThat(hostView(host).get("listing").get("keepFreeGb").asInt()).isEqualTo(10);
    }

    @Test
    void aListingWithoutKeepFreeKeeps20GbAndOutOfRangeValuesAreRefused() throws Exception {
        var host = host();
        assertThat(hostView(host).get("listing").get("keepFreeGb").asInt()).isEqualTo(20);
        for (var keepFree : new int[] {9, 501}) {
            mvc.perform(put("/v1/hosts/" + host.id() + "/listing")
                            .header("Authorization", host.agent().bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1,
                                     "keepFreeGb": %d}
                                    """.formatted(keepFree)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anOwnerRenamesAHostAndNobodyElseCan() throws Exception {
        var host = host();
        var other = TestUsers.signUp(mvc);

        mvc.perform(patch("/v1/hosts/" + host.id())
                        .header("Authorization", host.agent().bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  Gaming laptop  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gaming laptop"));
        mvc.perform(patch("/v1/hosts/" + host.id())
                        .header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Mine now\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/v1/hosts/" + host.id())
                        .header("Authorization", host.agent().bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest());
        assertThat(hostView(host).get("name").asString()).isEqualTo("Gaming laptop");
    }

    @Test
    void removingAnIdleHostRevokesItsCertificatesClosesItsStreamAndDropsItFromTheList() throws Exception {
        var host = host();
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ? and revoked_at is null",
                        Integer.class,
                        host.id()))
                .isPositive();

        mvc.perform(delete("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(status().isNoContent());

        assertThat(host.stream().endStatus(WAIT).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ? and revoked_at is null",
                        Integer.class,
                        host.id()))
                .isZero();
        assertThat(jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, host.id()))
                .isEqualTo("removed");
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.status_changes where host_id = ? and to_status = 'removed'",
                        Integer.class,
                        host.id()))
                .isOne();
        mvc.perform(get("/v1/hosts").header("Authorization", host.agent().bearer))
                .andExpect(jsonPath("$.hosts[?(@.id == '%s')]".formatted(host.id()))
                        .isEmpty());
        for (var request : List.of(
                get("/v1/hosts/" + host.id()),
                post("/v1/hosts/" + host.id() + "/enrolment-code"),
                post("/v1/hosts/" + host.id() + "/pause"),
                delete("/v1/hosts/" + host.id()))) {
            mvc.perform(request.header("Authorization", host.agent().bearer))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.type").value("/problems/host-removed"));
        }
        assertThat(fitNow()).isZero();
    }

    @Test
    void aHostRunningASessionCantBeRemoved() throws Exception {
        var host = host();
        var renter = TestUsers.signUp(mvc);
        TestCredit.grant(context, renter.id(), 50_000);
        var session = JSON.readTree(mvc.perform(post("/v1/sessions")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5,
                                         "maxSpendPaise": 10000}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        host.awaitOffer(WAIT);

        mvc.perform(delete("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/host-busy"));
        assertThat(jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, host.id()))
                .isEqualTo("active");
        assertThat(host.stream().receives(OpenStreamResponse::hasWelcome, Duration.ofMillis(100)))
                .isFalse();

        mvc.perform(post("/v1/sessions/" + session + "/cancel").header("Authorization", renter.bearer()));
    }
}
