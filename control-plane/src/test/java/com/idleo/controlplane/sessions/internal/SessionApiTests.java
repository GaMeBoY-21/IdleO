package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.sessions.SessionBookings;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Booking a session as a renter (ADR 0015). */
@ControlPlaneTest
class SessionApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionStore store;

    @Autowired
    SessionBookings bookings;

    @Autowired
    CreditReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TestUser renterWith(long paise) throws Exception {
        var renter = TestUsers.signUp(mvc);
        if (paise > 0) {
            TestCredit.grant(context, renter.id(), paise);
        }
        return renter;
    }

    private ResultActions book(TestUser renter, String key, String body) throws Exception {
        var request = post("/v1/sessions")
                .header("Authorization", renter.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", "\"" + key + "\"");
        }
        return mvc.perform(request);
    }

    private static String body(int maxPrice, long maxSpend) {
        return """
                {"templateId": "cuda-smoke-test", "maxPricePaisePerMin": %d, "maxSpendPaise": %d}
                """.formatted(maxPrice, maxSpend);
    }

    private int sessions(TestUser renter) {
        return jdbc.queryForObject(
                "select count(*) from sessions.sessions where renter_id = ?", Integer.class, renter.id());
    }

    @Test
    void aRenterWithCreditBooksASessionThatStartsRequested() throws Exception {
        var renter = renterWith(50_000);

        var created = book(renter, "book-1", body(10, 20_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateId").value("cuda-smoke-test"))
                .andExpect(jsonPath("$.vramNeededMb").value(1024))
                .andExpect(jsonPath("$.maxRuntimeSeconds").value(300))
                .andExpect(jsonPath("$.events[0].state").value("REQUESTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var id = JSON.readTree(created).get("id").asString();

        mvc.perform(get("/v1/sessions/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/v1/sessions").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.sessions[0].id").value(id));
    }

    @Test
    void aSessionThatNeverRanIsSettledAsSoonAsItEnds() throws Exception {
        var renter = renterWith(50_000);
        var id = JSON.readTree(book(renter, "never-ran", body(10, 20_000))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var fresh = JSON.readTree(mvc.perform(get("/v1/sessions/" + id).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        if (fresh.get("state").asString().equals("REQUESTED")
                || fresh.get("state").asString().equals("MATCHED")) {
            assertThat(fresh.get("settled").asBoolean()).isFalse();
            assertThat(fresh.get("endedAt").isNull()).isTrue();
        }

        mvc.perform(post("/v1/sessions/" + id + "/cancel").header("Authorization", renter.bearer()));

        var ended = JSON.readTree(mvc.perform(get("/v1/sessions/" + id).header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(ended.get("state").asString()).isIn("CANCELLED", "NO_HOST_FREE");
        assertThat(ended.get("settled").asBoolean())
                .as("ended and holding nothing")
                .isTrue();
        assertThat(ended.get("endedAt").isNull()).isFalse();
        assertThat(ended.get("reservedPaise").asLong()).isZero();
        assertThat(ended.get("gpu").isNull()).isTrue();
    }

    @Test
    void theSameKeyRepeatsTheSessionAndADifferentBodyIsRefused() throws Exception {
        var renter = renterWith(50_000);
        var first = JSON.readTree(book(renter, "book-2", body(10, 20_000))
                .andReturn()
                .getResponse()
                .getContentAsString());

        book(renter, "book-2", body(10, 20_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asString()));
        book(renter, "book-2", body(11, 20_000)).andExpect(status().isUnprocessableContent());
        book(renter, null, body(10, 20_000)).andExpect(status().isBadRequest());
        assertThat(sessions(renter)).isEqualTo(1);
    }

    @Test
    void tooLittleCreditIsRefusedBeforeAnythingIsCreated() throws Exception {
        var renter = renterWith(299);

        // 30 minutes at 10 paise is 300 paise.
        book(renter, "book-3", body(10, 20_000))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("300 paise")));
        assertThat(sessions(renter)).isZero();
        // A smaller maximum spend caps the hold.
        book(renter, "book-4", body(10, 299)).andExpect(status().isCreated());
    }

    @Test
    void theRequestMustFitTheTemplate() throws Exception {
        var renter = renterWith(50_000);
        for (var bad : new String[] {
            "{\"templateId\": \"no-such-template\", \"maxPricePaisePerMin\": 10, \"maxSpendPaise\": 1000}",
            "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 10, \"maxSpendPaise\": 1000, \"vramNeededMb\": 512}",
            "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 10, \"maxSpendPaise\": 1000, \"maxRuntimeSeconds\": 901}",
            "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 0, \"maxSpendPaise\": 1000}"
        }) {
            book(renter, "bad-" + UUID.randomUUID(), bad).andExpect(status().isBadRequest());
        }
        assertThat(sessions(renter)).isZero();
    }

    @Test
    void anotherRentersSessionIsNotFound() throws Exception {
        var renter = renterWith(50_000);
        var other = renterWith(0);
        var id = JSON.readTree(book(renter, "book-5", body(10, 20_000))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();

        mvc.perform(get("/v1/sessions/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/sessions/" + id + "/cancel").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingInEachCancellableStateReleasesTheHoldExactlyOnce() throws Exception {
        for (var target :
                new SessionState[] {SessionState.REQUESTED, SessionState.MATCHED, SessionState.PROVISIONING}) {
            var renter = renterWith(1_000);
            var session = SessionTestData.requested(store, transactionManager, renter.id(), 10, 20_000);
            if (target != SessionState.REQUESTED) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    bookings.match(session, 0, UUID.randomUUID(), 10);
                    reservations.reserve(renter.id(), session, Paise.of(300));
                    if (target == SessionState.PROVISIONING) {
                        bookings.offerAccepted(session, TestAgent.GPU_A);
                    }
                });
            }
            assertThat(store.find(session).orElseThrow().state()).isEqualTo(target);

            mvc.perform(post("/v1/sessions/" + session + "/cancel").header("Authorization", renter.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("CANCELLED"))
                    .andExpect(jsonPath("$.reservedPaise").value(0));
            mvc.perform(post("/v1/sessions/" + session + "/cancel").header("Authorization", renter.bearer()))
                    .andExpect(status().isOk());

            assertThat(reservations.available(renter.id())).as(target.name()).isEqualTo(Paise.of(1_000));
            assertThat(jdbc.queryForObject(
                            "select count(*) from billing.reservations where session_id = ? and status = 'released'",
                            Integer.class,
                            session))
                    .isEqualTo(target == SessionState.REQUESTED ? 0 : 1);
        }
    }

    @Test
    void aSessionThatRanCantBeCancelled() throws Exception {
        var renter = renterWith(1_000);
        var session = SessionTestData.requested(store, transactionManager, renter.id(), 10, 20_000);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> bookings.noHostFree(session, "no_candidate"));

        mvc.perform(post("/v1/sessions/" + session + "/cancel").header("Authorization", renter.bearer()))
                .andExpect(status().isConflict());
    }
}
