package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maintenance mode (ADR 0056): the app is read-only and every other write is 503 {@code maintenance} with Retry-After,
 * while the console, Razorpay's webhooks, sign-in and the few writes that only ever stop something keep working. The
 * agents, running sessions and metering are {@code PausedSessionsTests}'s, and RazorpayX's webhook {@code
 * PausedPayoutsTests}'.
 */
@ControlPlaneTest
class MaintenanceModeTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlers;

    @AfterEach
    void runEverythingAgain() {
        TestFlags.runAll(context);
    }

    @Test
    void exactlyTheseWritesStayOpen() {
        var open = new TreeSet<String>();
        handlers.getHandlerMethods().forEach((mapping, method) -> {
            if (method.hasMethodAnnotation(OpenDuringMaintenance.class)) {
                mapping.getMethodsCondition()
                        .getMethods()
                        .forEach(verb -> mapping.getPatternValues().forEach(path -> open.add(verb + " " + path)));
            }
        });
        assertThat(open)
                .containsExactly(
                        "DELETE /v1/me/deletion",
                        "POST /v1/cli-logins",
                        "POST /v1/cli-logins/{id}/approve",
                        "POST /v1/cli-logins/{id}/token",
                        "POST /v1/deletion-requests/{id}/cancel",
                        "POST /v1/email-events/resend",
                        "POST /v1/payments/razorpay/webhook",
                        "POST /v1/payouts/razorpayx/webhook",
                        "POST /v1/sessions/{id}/cancel",
                        "POST /v1/sessions/{id}/stop",
                        "POST /v1/wallet/topups/{id}/confirm");
    }

    @Test
    @TestFlags.Enforces(Flag.MAINTENANCE)
    void writesWaitWithRetryAfterWhileReadsAndTheConsoleWork() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        TestFlags.pause(context, Flag.MAINTENANCE, Instant.now().plus(Duration.ofMinutes(45)));

        var refused = mvc.perform(post("/v1/support/tickets")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"other\",\"message\":\"Hello there, a question.\",\"elapsedMs\":9000}"))
                .andReturn()
                .getResponse();
        assertThat(refused.getStatus()).isEqualTo(503);
        var problem = JSON.readTree(refused.getContentAsString());
        assertThat(problem.get("type").asString()).isEqualTo("/problems/maintenance");
        assertThat(problem.get("detail").asString())
                .startsWith("IdleO is down for maintenance: we expect to be back by");
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(2_600L, 2_700L);
        assertThat(mvc.perform(put("/v1/wallet/billing-details")
                                .header("Authorization", renter.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"stateCode\":\"29\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(503);

        assertThat(mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(admin.as(get("/admin/v1/flags")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        var adminWrite = mvc.perform(admin.as(post("/admin/v1/flags/topups/pause"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"belt and braces during maintenance\"}"))
                .andReturn()
                .getResponse();
        assertThat(adminWrite.getStatus()).as("the console writes").isEqualTo(200);
    }

    @Test
    @TestFlags.Enforces(Flag.MAINTENANCE)
    void aRazorpayWebhookStillLandsAndCreditsTheTopUp() throws Exception {
        var renter = TestUsers.signUp(mvc);
        mvc.perform(put("/v1/wallet/billing-details")
                .header("Authorization", renter.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stateCode\":\"29\"}"));
        var topUp = JSON.readTree(mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":30000}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");

        TestFlags.pause(context, Flag.MAINTENANCE);
        var captured = RAZORPAY.event("payment.captured", paid.paymentId());
        var landed = mvc.perform(post("/v1/payments/razorpay/webhook")
                        .header("X-Razorpay-Signature", captured.signature())
                        .header("x-razorpay-event-id", captured.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captured.body()))
                .andReturn()
                .getResponse();
        assertThat(landed.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select status from billing.topups where id = ?::uuid",
                        String.class,
                        topUp.get("id").asString()))
                .isEqualTo("paid");
    }

    @Test
    @TestFlags.Enforces(Flag.MAINTENANCE)
    void signingInWorksInTheBrowserAndFromTheCommandLine() throws Exception {
        TestFlags.pause(context, Flag.MAINTENANCE);
        var someoneNew = TestUsers.bearer(
                "maint-" + UUID.randomUUID(),
                "maint-" + UUID.randomUUID() + "@example.com",
                Instant.now(),
                Duration.ofHours(1));
        assertThat(mvc.perform(get("/v1/me").header("Authorization", someoneNew))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse started = mvc.perform(post("/v1/cli-logins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"build box\"}"))
                .andReturn()
                .getResponse();
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(201);
    }
}
