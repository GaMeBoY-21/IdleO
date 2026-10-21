package com.idleo.controlplane.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.PersonalDataMap;
import com.idleo.controlplane.identity.SignInAccounts;
import com.idleo.controlplane.identity.internal.ProviderAccountsProbe;
import com.idleo.controlplane.notifications.internal.RecordingEmailSender;
import com.idleo.controlplane.sessions.ErasureMarks;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Erasing an account (ADR 0037): afterwards, every table the personal data map finds the person in is as the map says
 * (deleted, or cleared of every personal column), nothing of theirs is left in storage, and nobody else's data is
 * touched; a job stopped after any step finishes the same; and after a restore of the database, the erased list erases
 * them again.
 */
@ControlPlaneTest
class ErasureTests {

    private static final Pattern LINK = Pattern.compile("request=([0-9a-f-]{36})&token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingEmailSender mail;

    @Autowired
    Erasure erasure;

    @Autowired
    SignInAccounts providers;

    @Autowired
    ErasureMarks marks;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private People.Made person(String name) throws Exception {
        return People.make(
                mvc,
                jdbc,
                environment,
                clock,
                name + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Asks, confirms with the emailed link and lets the wait pass: the request's id. */
    private UUID scheduled(People.Made person) throws Exception {
        mvc.perform(post("/v1/me/deletion").header("Authorization", person.signedInAt(clock.instant())))
                .andExpect(status().isAccepted());
        var email = mail.sent().reversed().stream()
                .filter(sent -> sent.to().equals(person.email()))
                .findFirst()
                .orElseThrow();
        var link = LINK.matcher(email.text());
        assertThat(link.find()).isTrue();
        mvc.perform(post("/v1/me/deletion/confirm")
                        .header("Authorization", person.user().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"token\":\"%s\"}".formatted(link.group(1), link.group(2))))
                .andExpect(status().isOk());
        clock.advance(Duration.ofDays(7).plusMinutes(1));
        return UUID.fromString(link.group(1));
    }

    private Map<String, Integer> counts(People.Made person) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var counts = new HashMap<String, Integer>();
        for (var table : PersonalDataMap.tables()) {
            counts.put(
                    table.name(),
                    PersonalDataMap.rows(named, table, person.ids()).size());
        }
        return counts;
    }

    private List<String> stored(People.Made person) {
        var bucket = environment.getRequiredProperty("sessions.storage.bucket");
        var prefixes = new java.util.ArrayList<String>();
        person.ids().uploads().forEach(id -> prefixes.add("uploads/" + id + "/"));
        person.ids().sessions().forEach(id -> prefixes.add("sessions/" + id + "/"));
        person.ids().hosts().forEach(id -> prefixes.add("speedtest/" + id + "/"));
        var keys = new java.util.ArrayList<String>();
        try (var s3 = People.storage(environment)) {
            for (var prefix : prefixes) {
                s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix))
                        .contents()
                        .forEach(object -> keys.add(object.key()));
            }
        }
        return keys;
    }

    /** Every table as the map says, and nothing of theirs in storage. */
    private void assertErased(People.Made person) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (var table : PersonalDataMap.tables()) {
            var rows = PersonalDataMap.rows(named, table, person.ids());
            switch (table.erasure()) {
                case DELETED ->
                    assertThat(rows)
                            .as("%s's rows in %s", person.marker(), table.name())
                            .isEmpty();
                case CLEARED -> {
                    for (var row : rows) {
                        for (var column : table.personal()) {
                            var value = row.get(column);
                            assertThat(value == null
                                            || List.of("", "{}", "[]")
                                                    .contains(value.toString().strip()))
                                    .as("%s.%s of %s after erasure: %s", table.name(), column, person.marker(), value)
                                    .isTrue();
                        }
                    }
                }
                case KEPT -> {}
            }
        }
        assertThat(stored(person))
                .as("%s's objects in storage", person.marker())
                .isEmpty();
        var everything = String.join(
                " ",
                jdbc.queryForList(
                        "select concat_ws(' ', email, display_name) from identity.users where id = ?",
                        String.class,
                        person.ids().user()));
        assertThat(everything).doesNotContain(person.marker());
    }

    @Test
    void leavesOnlyWhatTheMapKeepsAndNothingOfAnyoneElse() throws Exception {
        var asha = person("asha");
        var bina = person("bina");
        var binaBefore = counts(bina);
        var binaStored = stored(bina);
        var request = scheduled(asha);

        assertThat(erasure.erase(request, Erasure.STEPS)).isTrue();

        assertErased(asha);
        assertThat(counts(bina)).isEqualTo(binaBefore);
        assertThat(stored(bina)).containsExactlyInAnyOrderElementsOf(binaStored);
        assertThat(jdbc.queryForObject(
                        "select status from identity.users where id = ?",
                        String.class,
                        asha.ids().user()))
                .isEqualTo("erased");
        // The money is still in balance: what was left of the free credit went back through the ledger.
        assertThat(jdbc.queryForObject(
                        "select balance_paise from billing.wallets where user_id = ?",
                        Long.class,
                        asha.ids().user()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.ledger_entries where ref_type = 'credit_forfeit' and owner_id = ?",
                        Integer.class,
                        asha.ids().user()))
                .isOne();
        // Their sign-in provider's account went too, and nobody else's.
        assertThat(ProviderAccountsProbe.recorded(providers))
                .extracting(SignInAccounts.ProviderAccount::subject)
                .contains(asha.subject())
                .doesNotContain(bina.subject());
        // On the erased list, in the database and in storage.
        assertThat(jdbc.queryForObject(
                        "select count(*) from privacy.erased_users where user_id = ?",
                        Integer.class,
                        asha.ids().user()))
                .isOne();
        assertThat(marks.marked()).containsKey(asha.ids().user());
        // Each step audited by id only, the last email sent, nothing kept to send it with.
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'account.erase' and target_id = ?",
                        Integer.class,
                        asha.ids().user().toString()))
                .isEqualTo(Erasure.STEPS);
        assertThat(mail.sent().reversed().getFirst().subject()).isEqualTo("Your account is deleted");
        assertThat(mail.sent().reversed().getFirst().to()).isEqualTo(asha.email());
        assertThat(jdbc.queryForMap(
                        "select state, address, identities from privacy.deletion_requests where id = ?", request))
                .containsEntry("state", "erased")
                .containsEntry("address", null)
                .containsEntry("identities", null);
        // Nothing can sign in as them any more, whatever token they bring.
        mvc.perform(get("/v1/me").header("Authorization", asha.signedInAt(clock.instant())))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.users where email = ?", Integer.class, asha.email()))
                .as("a new sign-in is a new account, never the erased one")
                .isOne();
    }

    @Test
    void aJobStoppedAfterAnyStepFinishesTheSame() throws Exception {
        for (int stop = 1; stop < Erasure.STEPS; stop++) {
            var person = person("stop" + stop);
            var request = scheduled(person);
            assertThat(erasure.erase(request, stop)).isTrue();
            assertThat(jdbc.queryForObject(
                            "select step from privacy.deletion_requests where id = ?", Integer.class, request))
                    .isEqualTo(stop);
            // Picked up by the job's next run.
            assertThat(erasure.due()).contains(request);
            assertThat(erasure.erase(request, Erasure.STEPS)).isTrue();
            assertErased(person);
            assertThat(mail.sent().stream()
                            .filter(sent -> sent.to().equals(person.email()))
                            .filter(sent -> sent.subject().equals("Your account is deleted")))
                    .hasSize(1);
            clock.reset();
        }
    }

    @Test
    void waitsWhileSomethingStopsItThenGoesOn() throws Exception {
        var asha = person("asha");
        var request = scheduled(asha);
        jdbc.update(
                "update sessions.sessions set state = 'RUNNING' where id = ?",
                asha.ids().sessions().getFirst());
        assertThat(erasure.erase(request, Erasure.STEPS)).isFalse();
        assertThat(jdbc.queryForObject(
                        "select state from privacy.deletion_requests where id = ?", String.class, request))
                .isEqualTo("scheduled");
        jdbc.update(
                "update sessions.sessions set state = 'COMPLETED' where id = ?",
                asha.ids().sessions().getFirst());
        assertThat(erasure.erase(request, Erasure.STEPS)).isTrue();
        assertErased(asha);
    }

    @Test
    void afterARestoreTheErasedListErasesThemAgain() throws Exception {
        // Erased, and then a restore brings some of them back.
        var asha = person("asha");
        erasure.erase(scheduled(asha), Erasure.STEPS);
        clock.reset();
        jdbc.update(
                "update identity.users set status = 'active', email = ?, display_name = 'Asha' where id = ?",
                asha.email(),
                asha.ids().user());
        jdbc.update(
                "insert into notifications.notifications (id, user_id, kind, dedupe_key, title, body, link, created_at,"
                        + " in_app, email_wanted) values (?, ?, 'low-credit', 'restored', 'restored', '', '/', now(),"
                        + " true, false)",
                UUID.randomUUID(),
                asha.ids().user());
        // A person erased after the backup's point: on the list in storage only, all their data back.
        var bina = person("bina");
        marks.mark(bina.ids().user(), clock.instant());

        assertThat(erasure.reapply()).isGreaterThanOrEqualTo(2);

        assertErased(asha);
        assertErased(bina);
        assertThat(jdbc.queryForObject(
                        "select count(*) from privacy.erased_users where user_id = ?",
                        Integer.class,
                        bina.ids().user()))
                .isOne();
        // Nobody is emailed by a restore.
        assertThat(mail.sent().stream().filter(sent -> sent.to().equals(bina.email())))
                .noneMatch(sent -> sent.subject().equals("Your account is deleted"));
    }
}
