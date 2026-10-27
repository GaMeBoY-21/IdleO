package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The setup app's measurements: no sign-in, at most 20 events and 16 KiB a request and 200
 * an install, only the fields it allows; an install is tied to its host by its link, whose approval is its last click;
 * and the admin console's Installs card.
 */
@ControlPlaneTest
class HostInstallTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    private static String event(String step, String outcome, Instant at) {
        return """
                {"step":"%s","outcome":"%s","durationMs":1200,"at":"%s","setupVersion":"0.2.0",
                 "windowsBuild":26100,"gpuModel":"NVIDIA GeForce RTX 4080 Laptop GPU"}
                """.formatted(step, outcome, at);
    }

    private static String batch(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private org.springframework.test.web.servlet.ResultActions send(UUID install, String body) throws Exception {
        return mvc.perform(post("/v1/host-installs/" + install + "/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void eventsAreKeptWithoutASignInWithinTheirLimits() throws Exception {
        var install = UUID.randomUUID();
        var now = Instant.now(clock);
        var twenty = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            twenty.add(event("checks", "started", now));
        }
        send(install, batch(twenty.toArray(String[]::new))).andExpect(status().isNoContent());

        twenty.add(event("checks", "passed", now));
        send(install, batch(twenty.toArray(String[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        send(install, batch())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        // Only the fields it allows, in the forms it allows: a path in a reason is refused.
        send(
                        install,
                        batch(event("checks", "failed", now)
                                .replace("\"at\"", "\"reason\":\"C:\\\\\\\\Users\\\\\\\\nikki\",\"at\"")))
                .andExpect(status().isBadRequest());
        send(install, batch(event("checks", "skipped", now))).andExpect(status().isBadRequest());
        send(install, "not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/malformed-request"));
        send(install, "{\"events\":[" + "{\"step\":\"x\"},".repeat(1500) + "{}]}")
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("/problems/request-too-large"));

        // 200 in all for one install: 20 kept already, nine more batches of 20 fill it, and one more is refused whole.
        for (int i = 0; i < 9; i++) {
            send(install, batch(twenty.subList(0, 20).toArray(String[]::new))).andExpect(status().isNoContent());
        }
        send(install, batch(event("listed", "passed", now)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/install-events-full"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.install_events where install_id = ?", Integer.class, install))
                .isEqualTo(200);
    }

    /** Setup's list of every reason code it can send, written by its own tests ({@code agent/setup/src/reasons.rs}). */
    private static final java.nio.file.Path SETUP_REASONS = java.nio.file.Path.of("..", "api", "install-reasons.json");

    @Test
    void everyReasonCodeSetupCanSendIsOneThisServerAccepts() throws Exception {
        var list = json.readTree(java.nio.file.Files.readString(SETUP_REASONS));
        assertThat(list.get("pattern").asString())
                .as("the pattern setup believes the server uses")
                .isEqualTo(HostInstallController.REASON.pattern());
        assertThat(list.get("maxLength").asInt())
                .as("the longest reason setup believes the server takes")
                .isEqualTo(120);
        assertThat(list.get("reasons").size()).isGreaterThan(30);
        var install = UUID.randomUUID();
        var now = Instant.now(clock);
        var reasons = list.get("reasons").valueStream().map(r -> r.asString()).toList();
        // As the pattern says, and then through the endpoint itself, which is what counts.
        for (var reason : reasons) {
            assertThat(HostInstallController.REASON.matcher(reason).matches())
                    .as(reason)
                    .isTrue();
            assertThat(reason.length()).as(reason).isLessThanOrEqualTo(120);
        }
        for (int from = 0; from < reasons.size(); from += HostInstalls.MAX_PER_REQUEST) {
            var events = reasons.subList(from, Math.min(from + HostInstalls.MAX_PER_REQUEST, reasons.size())).stream()
                    .map(reason -> event("checks", "failed", now)
                            .replace("\"durationMs\"", "\"reason\":\"" + reason + "\",\"durationMs\""))
                    .toArray(String[]::new);
            send(UUID.randomUUID(), batch(events)).andExpect(status().isNoContent());
        }
        // And the codes this list was written to keep out are still refused.
        for (var bad : new String[] {"self_test:a,b", "Has Spaces", "C:\\\\Users"}) {
            send(
                            install,
                            batch(event("checks", "failed", now)
                                    .replace("\"durationMs\"", "\"reason\":\"" + bad + "\",\"durationMs\"")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aDetailIsKeptCutAtTwoThousandCharactersAndShownWithItsInstall() throws Exception {
        var install = UUID.randomUUID();
        var opened = Instant.now(clock).minus(Duration.ofMinutes(3));
        var long2500 = "é".repeat(2_500);
        send(
                        install,
                        batch(
                                event("setup_opened", "passed", opened),
                                event("self_test", "failed", opened.plusSeconds(30))
                                        .replace(
                                                "\"durationMs\"",
                                                "\"reason\":\"gpu_in_container\",\"detail\":\"nvidia-smi found no devices"
                                                        + " (took 101 ms)\",\"durationMs\""),
                                event("distro", "failed", opened.plusSeconds(31))
                                        .replace(
                                                "\"durationMs\"",
                                                "\"reason\":\"distro_import\",\"detail\":\"" + long2500
                                                        + "\",\"durationMs\""),
                                event("checks", "passed", opened.plusSeconds(32))))
                .andExpect(status().isNoContent());
        var stored = jdbc.queryForList(
                "select reason, length(detail) as length from hosts.install_events"
                        + " where install_id = ? and detail is not null order by at",
                install);
        assertThat(stored).hasSize(2);
        assertThat(((Number) stored.get(0).get("length")).intValue())
                .isEqualTo("nvidia-smi found no devices (took 101 ms)".length());
        assertThat(((Number) stored.get(1).get("length")).intValue()).isEqualTo(HostInstalls.MAX_DETAIL);

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var card = json.readTree(mvc.perform(admin.as(get("/admin/v1/installs")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var row = card.get("recent")
                .valueStream()
                .filter(r -> r.get("installId").asString().equals(install.toString()))
                .findFirst()
                .orElseThrow();
        var details = row.get("details");
        assertThat(details.size()).isEqualTo(2);
        assertThat(details.get(0).get("step").asString()).isEqualTo("self_test");
        assertThat(details.get(0).get("reason").asString()).isEqualTo("gpu_in_container");
        assertThat(details.get(0).get("detail").asString()).isEqualTo("nvidia-smi found no devices (took 101 ms)");
        assertThat(details.get(1).get("detail").asString()).hasSize(HostInstalls.MAX_DETAIL);
    }

    @Test
    void factsAreKeptAsPassedEventsAndAreNeverWhereAnInstallStopped() throws Exception {
        var install = UUID.randomUUID();
        var opened = Instant.now(clock).minus(Duration.ofMinutes(2));
        send(
                        install,
                        batch(
                                event("setup_opened", "passed", opened),
                                event("self_test", "failed", opened.plusSeconds(30))
                                        .replace(
                                                "\"durationMs\"",
                                                "\"reason\":\"lockdown\",\"detail\":\"a shell answered\",\"durationMs\""),
                                event("facts", "passed", opened.plusSeconds(31))
                                        .replace(
                                                "\"durationMs\"",
                                                "\"reason\":\"facts_network\",\"detail\":\"reach_gateway: reachable\\nreach_lan_10_0_0_1: silent\","
                                                        + "\"durationMs\"")))
                .andExpect(status().isNoContent());
        var stored = jdbc.queryForObject(
                "select detail from hosts.install_events where install_id = ? and step = 'facts'",
                String.class,
                install);
        assertThat(stored).isEqualTo("reach_gateway: reachable\nreach_lan_10_0_0_1: silent");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var card = json.readTree(mvc.perform(admin.as(get("/admin/v1/installs")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var row = card.get("recent")
                .valueStream()
                .filter(r -> r.get("installId").asString().equals(install.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(row.get("stoppedAt").asString()).isEqualTo("self_test failed: lockdown");
        assertThat(row.get("details").size())
                .as("only failures are shown on the card")
                .isEqualTo(1);
    }

    @Test
    void aSplitSurrogatePairIsNeverLeftAtTheCut() {
        var atTheCut = "a".repeat(HostInstalls.MAX_DETAIL - 1) + "\uD83D\uDE00";
        var cut = HostInstalls.truncated(atTheCut);
        assertThat(cut).hasSize(HostInstalls.MAX_DETAIL - 1);
        assertThat(HostInstalls.truncated("short")).isEqualTo("short");
        assertThat(HostInstalls.truncated(null)).isNull();
    }

    @Test
    void anInstallIsTiedToItsHostAndCountedOnTheAdminCard() throws Exception {
        var install = UUID.randomUUID();
        var opened = Instant.now(clock).minus(Duration.ofMinutes(4));
        send(
                        install,
                        batch(
                                event("setup_opened", "passed", opened),
                                event("click", "passed", opened.plusSeconds(5))
                                        .replace("\"durationMs\"", "\"reason\":\"set_up\",\"durationMs\""),
                                event("checks", "started", opened.plusSeconds(6)),
                                event("checks", "passed", opened.plusSeconds(20))))
                .andExpect(status().isNoContent());
        var link = json.readTree(mvc.perform(post("/v1/host-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineName":"ASUS ROG Strix","os":"windows","agentVersion":"0.1.0",
                                 "gpus":[{"model":"NVIDIA GeForce RTX 4080 Laptop GPU","vramMb":12282}],
                                 "installId":"%s"}
                                """.formatted(install)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var owner = TestUsers.signUpLasting(mvc, Duration.ofHours(2));
        mvc.perform(post("/v1/host-links/" + link.get("id").asString() + "/approve")
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nikki's laptop\",\"batchPricePaisePerMin\":18}"))
                .andExpect(status().isOk());
        send(install, batch(event("listed", "passed", opened.plus(Duration.ofMinutes(4)))))
                .andExpect(status().isNoContent());

        var stuck = UUID.randomUUID();
        send(
                        stuck,
                        batch(
                                event("setup_opened", "passed", opened),
                                event("checks", "failed", opened.plusSeconds(9))
                                        .replace("\"durationMs\"", "\"reason\":\"driver_too_old\",\"durationMs\"")))
                .andExpect(status().isNoContent());

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var card = json.readTree(mvc.perform(admin.as(get("/admin/v1/installs")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var rows = card.get("recent");
        var listed = rows.valueStream()
                .filter(row -> row.get("installId").asString().equals(install.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(listed.get("minutes").asDouble()).isEqualTo(4.0);
        assertThat(listed.get("clicks").asInt()).isEqualTo(2);
        assertThat(listed.get("stoppedAt").asString()).isEqualTo("listed");
        assertThat(listed.get("hostId").isNull()).isFalse();
        var failed = rows.valueStream()
                .filter(row -> row.get("installId").asString().equals(stuck.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.get("stoppedAt").asString()).isEqualTo("checks failed: driver_too_old");
        assertThat(failed.get("minutes").isNull()).isTrue();
        assertThat(card.get("medianMinutes").isNull()).isFalse();
        assertThat(card.get("failures")
                        .valueStream()
                        .anyMatch(row -> row.get("reason").asString().equals("driver_too_old")))
                .isTrue();
    }
}
