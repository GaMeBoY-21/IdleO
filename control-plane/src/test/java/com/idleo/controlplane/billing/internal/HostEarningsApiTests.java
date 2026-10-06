package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A host's owner sees what it earned (ADR 0017): its payable balance and history, and nobody else does. */
@ControlPlaneTest
class HostEarningsApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    SessionCharges charges;

    @Autowired
    CreditReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    private record Owned(TestUsers.TestUser owner, UUID hostId) {}

    private Owned host() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var body = mvc.perform(post("/v1/hosts")
                        .header("Authorization", owner.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Earning rig\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Owned(owner, UUID.fromString(JSON.readTree(body).get("id").asString()));
    }

    /** A session of {@code minutes} at 2 paise on the host, settled: the host earns 80%, rounded down. */
    private UUID settled(UUID hostId, int minutes) throws Exception {
        var renter = TestUsers.signUp(mvc).id();
        TestCredit.grant(context, renter, 1_000);
        var sessionId = UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> reservations.reserve(renter, sessionId, Paise.of(60)));
        tx.executeWithoutResult(status -> charges.settle(new SessionCharges.Charge(
                sessionId,
                renter,
                UUID.randomUUID(),
                List.of(new SessionCharges.Part(hostId, Paise.of(2L * minutes))),
                Paise.of(60))));
        if (minutes == 3) {
            tx.executeWithoutResult(status -> charges.refund(
                    sessionId,
                    renter,
                    UUID.randomUUID(),
                    List.of(new SessionCharges.RefundPart(hostId, Paise.of(1), Paise.of(1)))));
        }
        return sessionId;
    }

    @Test
    void theOwnerSeesTheBalanceAndEveryCreditAndDebitNewestFirst() throws Exception {
        var host = host();
        mvc.perform(get("/v1/hosts/" + host.hostId() + "/earnings")
                        .header("Authorization", host.owner().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.history.entries").isEmpty())
                .andExpect(jsonPath("$.history.nextCursor").doesNotExist());

        // 10 paise earns 8; 6 earns 4; then 2 paise of the 6 are refunded, 1 from the host.
        settled(host.hostId(), 5);
        var refunded = settled(host.hostId(), 3);

        var kinds = new ArrayList<String>();
        var amounts = new ArrayList<Long>();
        String cursor = null;
        do {
            var request = get("/v1/hosts/" + host.hostId() + "/earnings")
                    .header("Authorization", host.owner().bearer())
                    .param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            var page = JSON.readTree(mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balancePaise").value(8 + 4 - 1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            for (var entry : page.get("history").get("entries")) {
                kinds.add(entry.get("kind").asString() + ":"
                        + entry.get("direction").asString());
                amounts.add(entry.get("amountPaise").asLong());
            }
            cursor = page.get("history").get("nextCursor").isNull()
                    ? null
                    : page.get("history").get("nextCursor").asString();
        } while (cursor != null);

        assertThat(kinds)
                .containsExactly("lost_minutes_refunded:debit", "session_earning:credit", "session_earning:credit");
        assertThat(amounts).containsExactly(1L, 4L, 8L);
        mvc.perform(get("/v1/hosts/" + host.hostId() + "/earnings")
                        .header("Authorization", host.owner().bearer()))
                .andExpect(jsonPath("$.history.entries[0].reference.type").value("session_refund"))
                .andExpect(jsonPath("$.history.entries[0].reference.id").value(refunded.toString()));
    }

    @Test
    void anotherUsersHostOrNoHostIsNotFound() throws Exception {
        var host = host();
        var stranger = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/hosts/" + host.hostId() + "/earnings").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/hosts/" + UUID.randomUUID() + "/earnings")
                        .header("Authorization", host.owner().bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/hosts/" + host.hostId() + "/earnings")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadLimitOrCursorIsABadRequest() throws Exception {
        var host = host();
        var earnings = "/v1/hosts/" + host.hostId() + "/earnings";

        mvc.perform(get(earnings).header("Authorization", host.owner().bearer()).param("limit", "51"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(earnings).header("Authorization", host.owner().bearer()).param("cursor", "nonsense"))
                .andExpect(status().isBadRequest());
    }
}
