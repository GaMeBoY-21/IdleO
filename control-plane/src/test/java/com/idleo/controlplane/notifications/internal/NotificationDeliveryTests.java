package com.idleo.controlplane.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionCosts;
import com.idleo.controlplane.sessions.SessionLowOnCredit;
import com.idleo.controlplane.sessions.SessionNeverRan;
import com.idleo.controlplane.sessions.SessionOutcomes;
import com.idleo.controlplane.sessions.SessionOutcomes.Outcome;
import com.idleo.controlplane.sessions.SessionSettled;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each kind of message is made once from its event, respects the person's choices, and is emailed at most once
 * (ADR 0026). The listeners run against stand-ins for the sessions and hosts ports, so each case is exact; the
 * real events reach them in the flow tests.
 */
@ControlPlaneTest
class NotificationDeliveryTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Messages messages;

    @Autowired
    Notifier notifier;

    @Autowired
    RecordingEmailSender mail;

    private TestUsers.TestUser renter;
    private String renterEmail;
    private final Map<UUID, Outcome> outcomes = new HashMap<>();
    private final Map<String, UUID> hosts = new HashMap<>();
    private final Map<UUID, UUID> owners = new HashMap<>();
    private final Map<UUID, String> machineNames = new HashMap<>();
    private long charged = 2;
    private NotificationListeners listeners;

    @BeforeEach
    void setUp() throws Exception {
        mail.clear();
        renter = TestUsers.signUp(mvc);
        renterEmail = emailOf(renter);
        SessionOutcomes sessions = new SessionOutcomes() {
            @Override
            public Optional<Outcome> of(UUID sessionId) {
                return Optional.ofNullable(outcomes.get(sessionId));
            }

            @Override
            public Optional<UUID> hostOfAttempt(UUID sessionId, int attempt) {
                return Optional.ofNullable(hosts.get(sessionId + ":" + attempt));
            }
        };
        SessionCosts costs = sessionId -> new SessionCosts.Cost(charged, 0, false, true);
        HostOwners hostOwners = new HostOwners() {
            @Override
            public boolean owns(UUID userId, UUID hostId) {
                return userId.equals(owners.get(hostId));
            }

            @Override
            public Optional<UUID> ownerOf(UUID hostId) {
                return Optional.ofNullable(owners.get(hostId));
            }

            @Override
            public List<OwnedHost> ownedBy(UUID userId) {
                return owners.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(userId))
                        .map(entry -> new OwnedHost(entry.getKey(), machineNames.get(entry.getKey()), false))
                        .toList();
            }
        };
        listeners = new NotificationListeners(sessions, costs, hostOwners, messages, notifier);
    }

    private String emailOf(TestUsers.TestUser user) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/me").header("Authorization", user.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("email")
                .asString();
    }

    private UUID session(String state, String endCode, String stoppedFor, boolean ran) {
        var id = UUID.randomUUID();
        outcomes.put(
                id,
                new Outcome(
                        id, renter.id(), "Fine-tune a model", state, endCode, stoppedFor, ran ? Instant.now() : null));
        return id;
    }

    private List<Map<String, Object>> inbox(UUID userId) {
        return jdbc.queryForList(
                "select kind, title, body, link, in_app, email_wanted, email_sent_at, email_claimed_at"
                        + " from notifications.notifications where user_id = ? order by id",
                userId);
    }

    private void choose(TestUsers.TestUser user, String kind, boolean email, boolean inApp) throws Exception {
        mvc.perform(put("/v1/me/notifications")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"kind\":\"" + kind + "\",\"email\":" + email + ",\"inApp\":" + inApp
                                + "}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void aCompletedSessionTellsTheRenterItsChargeAndLinksToItsOutputs() {
        var id = session("COMPLETED", null, null, true);
        charged = 9;

        listeners.on(new SessionSettled(id));

        var messages = inbox(renter.id());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst())
                .containsEntry("kind", "session-completed")
                .containsEntry("title", "Fine-tune a model finished")
                .containsEntry("body", "It cost ₹0.09. Your outputs are ready to download.")
                .containsEntry("link", "/session/?id=" + id + "#outputs");
        assertThat(mail.sent()).hasSize(1);
        assertThat(mail.sent().getFirst().to()).isEqualTo(renterEmail);
        assertThat(mail.sent().getFirst().subject()).isEqualTo("Fine-tune a model finished");
        assertThat(mail.sent().getFirst().text()).contains("It cost ₹0.09.");
    }

    @Test
    void anEventDeliveredAgainMakesNoSecondMessageAndNoSecondEmail() {
        var id = session("COMPLETED", null, null, true);

        listeners.on(new SessionSettled(id));
        listeners.on(new SessionSettled(id));
        listeners.on(new SessionSettled(id));

        assertThat(inbox(renter.id())).hasSize(1);
        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void aFailedSessionSaysWhyInPlainWordsAndWhatItCost() {
        var id = session("FAILED", "oom_killed", null, true);
        charged = 4;

        listeners.on(new SessionSettled(id));

        var message = inbox(renter.id()).getFirst();
        assertThat(message.get("kind")).isEqualTo("session-failed");
        assertThat(message.get("title")).isEqualTo("Fine-tune a model didn't finish");
        assertThat(message.get("body"))
                .isEqualTo("The job ran out of memory. It cost ₹0.04. Choose more GPU memory, or a smaller model"
                        + " or batch size, and run it again.");
        assertThat(mail.sent().getFirst().subject()).isEqualTo("Fine-tune a model didn't finish");
    }

    @Test
    void aSessionThatFailedBeforeItRanSaysNothingWasCharged() {
        var id = session("FAILED", "start_timeout", null, false);

        listeners.on(new SessionNeverRan(id));

        assertThat(inbox(renter.id()).getFirst().get("body"))
                .isEqualTo("The host didn't start it in time. You weren't charged. Run it again; you won't be"
                        + " charged for the attempt that failed to start.");
    }

    @Test
    void aSessionStoppedForCreditOrTheCapIsMoneyAndAlwaysSent() throws Exception {
        for (var reason : List.of("credit_exhausted", "max_spend_reached")) {
            var user = TestUsers.signUp(mvc);
            var id = UUID.randomUUID();
            outcomes.put(id, new Outcome(id, user.id(), "Fine-tune a model", "COMPLETED", null, reason, Instant.now()));
            charged = 6;

            listeners.on(new SessionSettled(id));

            var message = inbox(user.id()).getFirst();
            assertThat(message).containsEntry("kind", "stopped-for-credit");
            assertThat(message.get("title"))
                    .isEqualTo(
                            reason.equals("max_spend_reached")
                                    ? "Stopped: it reached your spending cap"
                                    : "Stopped: your credit ran out");
            assertThat(message.get("body").toString()).contains("It cost ₹0.06.");
            var userEmail = emailOf(user);
            assertThat(mail.sent()).anyMatch(email -> email.to().equals(userEmail));
        }
    }

    @Test
    void aSessionTheRenterStoppedThemselvesSaysNothing() {
        var id = session("COMPLETED", null, "renter_stopped", true);

        listeners.on(new SessionSettled(id));

        assertThat(inbox(renter.id())).isEmpty();
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void lowCreditIsToldOncePerSessionHoweverOftenItFallsUnder() {
        var id = session("RUNNING", null, null, true);

        listeners.on(new SessionLowOnCredit(id));
        listeners.on(new SessionLowOnCredit(id));
        var other = session("RUNNING", null, null, true);
        listeners.on(new SessionLowOnCredit(other));

        assertThat(inbox(renter.id())).hasSize(2);
        assertThat(mail.sent()).hasSize(2);
        assertThat(inbox(renter.id()).getFirst())
                .containsEntry("kind", "low-credit")
                .containsEntry("title", "Your credit is running low");
    }

    @Test
    void lowCreditAfterTheSessionHasEndedIsNotWorthSaying() {
        var id = session("COMPLETED", null, null, true);

        listeners.on(new SessionLowOnCredit(id));

        assertThat(inbox(renter.id())).isEmpty();
    }

    @Test
    void noGpuFreeIsOnlyInTheApp() {
        var id = session("NO_HOST_FREE", "no_candidate", null, false);

        listeners.on(new SessionNeverRan(id));

        var message = inbox(renter.id()).getFirst();
        assertThat(message).containsEntry("kind", "no-host-free").containsEntry("title", "No GPU was free");
        assertThat(message.get("body").toString()).contains("You weren't charged.");
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void aLostHostMovesTheRenterOnAndTellsTheOwnerTheirMachineWentOffline() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var host = UUID.randomUUID();
        owners.put(host, owner.id());
        machineNames.put(host, "ROG laptop");
        var id = session("HOST_LOST", null, null, true);
        hosts.put(id + ":1", host);

        listeners.on(new AttemptLost(id, 1));
        listeners.on(new AttemptLost(id, 1));

        var renterMessage = inbox(renter.id());
        assertThat(renterMessage).hasSize(1);
        assertThat(renterMessage.getFirst()).containsEntry("kind", "host-lost");
        assertThat(mail.sent()).noneMatch(email -> email.to().equals(renterEmail));

        var ownerMessages = inbox(owner.id());
        assertThat(ownerMessages).hasSize(1);
        assertThat(ownerMessages.getFirst())
                .containsEntry("kind", "machine-offline")
                .containsEntry("title", "ROG laptop went offline during a session")
                .containsEntry("link", "/hosting/machine/?id=" + host);
        assertThat(mail.sent()).hasSize(1);
        var email = mail.sent().getFirst();
        assertThat(email.to()).isEqualTo(emailOf(owner));
        assertThat(email.text() + email.html())
                .as("the owner never sees the renter or what they ran")
                .doesNotContain(renterEmail)
                .doesNotContain("Fine-tune")
                .doesNotContain(id.toString());
    }

    @Test
    void emailOffKeepsTheMessageInTheAppAndInAppOffKeepsTheEmail() throws Exception {
        choose(renter, "session-completed", false, true);
        var first = session("COMPLETED", null, null, true);
        listeners.on(new SessionSettled(first));
        assertThat(inbox(renter.id())).hasSize(1);
        assertThat(mail.sent()).isEmpty();

        choose(renter, "session-completed", true, false);
        var second = session("COMPLETED", null, null, true);
        listeners.on(new SessionSettled(second));
        var rows = inbox(renter.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.getLast()).containsEntry("in_app", false).containsEntry("email_wanted", true);
        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void bothOffMeansNothingIsMadeOrSent() throws Exception {
        choose(renter, "session-failed", false, false);
        var id = session("FAILED", "job_failed", null, true);

        listeners.on(new SessionSettled(id));

        assertThat(inbox(renter.id())).isEmpty();
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void aMailServerThatRefusesLosesNothingAndTheRetrySendsItOnce() {
        var id = session("COMPLETED", null, null, true);
        mail.refuseNext(1);

        assertThatThrownBy(() -> listeners.on(new SessionSettled(id)))
                .isInstanceOf(EmailSender.EmailFailedException.class);
        var after = inbox(renter.id());
        assertThat(after).hasSize(1);
        assertThat(after.getFirst()).containsEntry("email_claimed_at", null).containsEntry("email_sent_at", null);
        assertThat(mail.sent()).isEmpty();

        listeners.on(new SessionSettled(id));
        listeners.on(new SessionSettled(id));

        assertThat(inbox(renter.id())).hasSize(1);
        assertThat(mail.sent()).hasSize(1);
        assertThat(inbox(renter.id()).getFirst().get("email_sent_at")).isNotNull();
    }

    @Test
    void aPersonWithoutAVerifiedAddressStillGetsTheMessageInTheApp() {
        var stranger = UUID.randomUUID();
        notifier.tell(
                stranger,
                NotificationKind.SESSION_COMPLETED,
                "test:" + stranger,
                new Message("T", "B", "/session/?id=1"));

        assertThat(inbox(stranger)).hasSize(1);
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void aSessionThatIsGoneIsLoggedNotRetriedForever() {
        listeners.on(new SessionSettled(UUID.randomUUID()));
        listeners.on(new SessionNeverRan(UUID.randomUUID()));
        listeners.on(new SessionLowOnCredit(UUID.randomUUID()));
        listeners.on(new AttemptLost(UUID.randomUUID(), 1));

        assertThat(mail.sent()).isEmpty();
    }
}
