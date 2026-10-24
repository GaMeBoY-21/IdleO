package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.razorpay.FakeRazorpayX;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hosts who earn and get paid, for the payouts tests (ADR 0040): an owner signed in for a month (so the clock may move
 * past a dispute window), their machine, sessions that earn on it, and a verified PAN and UPI ID through the API.
 */
final class Payees {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration LIFETIME = Duration.ofDays(60);

    private final MockMvc mvc;
    private final ApplicationContext context;
    private final MutableClock clock;

    Payees(MockMvc mvc, ApplicationContext context) {
        this.mvc = mvc;
        this.context = context;
        this.clock = context.getBean(MutableClock.class);
    }

    /** A host's owner, and their first machine. */
    record Owner(String subject, String email, UUID id, UUID hostId) {

        /** A bearer as of a sign-in now (a fresh one, for what needs it). */
        String bearer(MutableClock clock) {
            return TestUsers.bearer(subject, email, clock.instant(), LIFETIME);
        }
    }

    Owner owner() throws Exception {
        var subject = "payee-" + UUID.randomUUID();
        var email = subject + "@example.com";
        var user = TestUsers.signUpAs(mvc, subject, email, LIFETIME);
        var owner = new Owner(subject, email, user.id(), null);
        return new Owner(subject, email, user.id(), machine(owner, "Payee laptop"));
    }

    UUID machine(Owner owner, String name) throws Exception {
        var body = mvc.perform(post("/v1/hosts")
                        .header("Authorization", owner.bearer(clock))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    /** A session on the machine that charged {@code chargedPaise}, settled: the host earns 80% of it. */
    Earned earn(UUID hostId, long chargedPaise) {
        var renterId = renterWithCredit(chargedPaise * 2 + 1_000);
        var sessionId = UUID.randomUUID();
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var reservations = context.getBean(CreditReservations.class);
        var charges = context.getBean(SessionCharges.class);
        tx.executeWithoutResult(status -> reservations.reserve(renterId, sessionId, Paise.of(chargedPaise + 100)));
        tx.executeWithoutResult(status -> charges.settle(new SessionCharges.Charge(
                sessionId,
                renterId,
                UUID.randomUUID(),
                List.of(new SessionCharges.Part(hostId, Paise.of(chargedPaise))),
                Paise.of(100))));
        return new Earned(sessionId, renterId, chargedPaise * 80 / 100);
    }

    record Earned(UUID sessionId, UUID renterId, long hostPaise) {}

    private UUID renterWithCredit(long paise) {
        try {
            var renter = TestUsers.signUpLasting(mvc, LIFETIME);
            TestCredit.grant(context, renter.id(), paise);
            return renter.id();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A PAN the fake verifies (any well-formed one but digits 0000 and 9999), its name as on it. */
    static String pan() {
        return "ABCPE" + String.format("%04d", 1000 + (int) (Math.random() * 8000)) + "F";
    }

    JsonNode submitPan(Owner owner, String pan, String name) throws Exception {
        return JSON.readTree(mvc.perform(put("/v1/payout-details/pan")
                        .header("Authorization", owner.bearer(clock))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"" + pan + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    JsonNode check(Owner owner, String vpa) throws Exception {
        return JSON.readTree(mvc.perform(post("/v1/payout-details/upi-checks")
                        .header("Authorization", owner.bearer(clock))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vpa\":\"" + vpa + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    JsonNode confirm(Owner owner, String checkId) throws Exception {
        return JSON.readTree(mvc.perform(post("/v1/payout-details/upi-checks/" + checkId + "/confirm")
                        .header("Authorization", owner.bearer(clock)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** A verified PAN and an active UPI ID: payouts may go to them. */
    Owner verified(Owner owner, String vpa) throws Exception {
        assertThat(submitPan(owner, pan(), "Asha Rao").get("state").asString()).isEqualTo("verified");
        var check = check(owner, vpa);
        assertThat(check.get("state").asString()).isEqualTo("checked");
        assertThat(confirm(owner, check.get("id").asString()).get("state").asString())
                .isEqualTo("active");
        return owner;
    }

    /** An owner with a machine, a verified PAN and UPI ID, and one session's earnings already available. */
    Owner earningAndVerified(long chargedPaise) throws Exception {
        var owner = verified(owner(), "host" + UUID.randomUUID().toString().substring(0, 8) + "@okicici");
        earn(owner.hostId(), chargedPaise);
        return owner;
    }

    /** Posts a signed RazorpayX event as RazorpayX would. */
    void webhook(FakeRazorpayX.Signed signed) throws Exception {
        mvc.perform(post("/v1/payouts/razorpayx/webhook")
                        .header("X-Razorpay-Signature", signed.signature())
                        .header("x-razorpay-event-id", signed.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signed.body()))
                .andExpect(status().isOk());
    }

    /** The one message told for {@code dedupeKey}, once the durable listener has run: its body. */
    static String told(JdbcTemplate jdbc, UUID userId, String dedupeKey) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "select count(*) from notifications.notifications where user_id = ? and dedupe_key = ?",
                                Integer.class,
                                userId,
                                dedupeKey)
                        == 1);
        return jdbc.queryForObject(
                "select body from notifications.notifications where user_id = ? and dedupe_key = ?",
                String.class,
                userId,
                dedupeKey);
    }

    /** What IdleO has absorbed in chargeback losses and waived clawbacks, in all. */
    static long losses(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select coalesce(sum(case direction when 'debit' then amount_paise else -amount_paise end), 0)
                from billing.ledger_entries where account = 'chargeback_losses'
                """, Long.class);
    }

    /** The owner's balance on one account of the ledger, as it's raised. */
    static long balance(JdbcTemplate jdbc, String account, UUID owner) {
        var raisedByDebits = account.equals("host_receivable");
        long net = jdbc.queryForObject("""
                select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                from billing.ledger_entries where account = ? and owner_id = ?
                """, Long.class, account, owner);
        return raisedByDebits ? -net : net;
    }
}
