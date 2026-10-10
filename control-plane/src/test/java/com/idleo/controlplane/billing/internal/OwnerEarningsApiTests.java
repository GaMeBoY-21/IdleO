package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A person's earnings across their machines (ADR 0023) are the sum of the ledger's entries, and only theirs. */
@ControlPlaneTest
class OwnerEarningsApiTests {

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

    @Autowired
    JdbcTemplate jdbc;

    private UUID host(TestUser owner, String name) throws Exception {
        var body = mvc.perform(post("/v1/hosts")
                        .header("Authorization", owner.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    /** A session of {@code minutes} at 2 paise on the host, settled; with {@code giveBack}, 1 paisa of it refunded. */
    private void settled(UUID hostId, int minutes, boolean giveBack) throws Exception {
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
        if (giveBack) {
            tx.executeWithoutResult(status -> charges.refund(
                    sessionId,
                    renter,
                    UUID.randomUUID(),
                    List.of(new SessionCharges.RefundPart(hostId, Paise.of(1), Paise.of(1)))));
        }
    }

    private long ledgerNet(UUID... hostIds) {
        long net = 0;
        for (var hostId : hostIds) {
            net += jdbc.queryForObject("""
                            select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                            from billing.ledger_entries where account = 'host_payable' and owner_id = ?
                            """, Long.class, hostId);
        }
        return net;
    }

    private JsonNode earnings(TestUser owner, String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/earnings" + query).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void earningsAcrossTwoMachinesWithAGivebackEqualTheLedgerEntries() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var home = host(owner, "Home desktop");
        var laptop = host(owner, "ROG Strix");
        settled(home, 5, false);
        settled(laptop, 3, true);
        settled(laptop, 10, false);

        var result = earnings(owner, "");

        assertThat(result.get("totalPaise").asLong()).isEqualTo(ledgerNet(home, laptop));
        assertThat(result.get("totalPaise").asLong()).isEqualTo(8 + 4 - 1 + 16);
        assertThat(result.get("weekPaise").asLong()).isEqualTo(ledgerNet(home, laptop));
        assertThat(result.get("payoutsStart").asString()).isEqualTo("paid-pilot");
        var machines = result.get("machines");
        assertThat(machines).hasSize(2);
        assertThat(machines.get(0).get("name").asString()).isEqualTo("Home desktop");
        assertThat(machines.get(0).get("earnedPaise").asLong()).isEqualTo(ledgerNet(home));
        assertThat(machines.get(1).get("earnedPaise").asLong()).isEqualTo(ledgerNet(laptop));
        var days = result.get("days");
        assertThat(days).hasSize(7);
        var today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(days.get(6).get("date").asString()).isEqualTo(today.toString());
        assertThat(days.get(6).get("earnedPaise").asLong()).isEqualTo(ledgerNet(home, laptop));
        assertThat(days.get(0).get("earnedPaise").asLong()).isZero();
        var weekStart =
                java.time.Instant.parse(result.get("weekStartsAt").asString()).atZone(ZoneId.of("Asia/Kolkata"));
        assertThat(weekStart.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(weekStart.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
    }

    @Test
    void theHistoryPagesAcrossMachinesNewestFirstAndAGivebackIsNegative() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var home = host(owner, "Home desktop");
        var laptop = host(owner, "ROG Strix");
        settled(home, 5, false);
        settled(laptop, 3, true);

        var amounts = new ArrayList<Long>();
        var labels = new ArrayList<String>();
        var machines = new ArrayList<String>();
        String cursor = null;
        do {
            var page = earnings(owner, "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))
                    .get("history");
            for (var entry : page.get("entries")) {
                amounts.add(entry.get("amountPaise").asLong());
                labels.add(entry.get("label").asString());
                machines.add(entry.get("machineName").asString());
            }
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(amounts).containsExactly(-1L, 4L, 8L);
        assertThat(amounts.stream().mapToLong(Long::longValue).sum()).isEqualTo(ledgerNet(home, laptop));
        assertThat(labels.getFirst()).isEqualTo("Given back for lost time");
        assertThat(machines).containsExactly("ROG Strix", "ROG Strix", "Home desktop");
    }

    @Test
    void aRemovedMachineKeepsItsEarningsInTheTotalAndTheMachineList() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var kept = host(owner, "Kept");
        var gone = host(owner, "Gone");
        settled(kept, 5, false);
        settled(gone, 5, false);
        jdbc.update("update hosts.hosts set status = 'removed', removed_at = now() where id = ?", gone);

        var result = earnings(owner, "");

        assertThat(result.get("totalPaise").asLong()).isEqualTo(16);
        assertThat(result.get("machines")).hasSize(2);
        assertThat(result.get("machines").get(1).get("removed").asBoolean()).isTrue();
        assertThat(result.get("machines").get(1).get("earnedPaise").asLong()).isEqualTo(8);
    }

    @Test
    void nobodyElsesEarningsAppearAndAPersonWithoutMachinesSeesZeros() throws Exception {
        var owner = TestUsers.signUp(mvc);
        settled(host(owner, "Mine"), 5, false);
        var stranger = TestUsers.signUp(mvc);

        var result = earnings(stranger, "");

        assertThat(result.get("totalPaise").asLong()).isZero();
        assertThat(result.get("weekPaise").asLong()).isZero();
        assertThat(result.get("machines")).isEmpty();
        assertThat(result.get("history").get("entries")).isEmpty();
        assertThat(result.get("days")).hasSize(7);
        mvc.perform(get("/v1/earnings?limit=0").header("Authorization", stranger.bearer()))
                .andExpect(status().isBadRequest());
    }
}
