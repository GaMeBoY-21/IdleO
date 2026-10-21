package com.idleo.controlplane.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.People;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.PersonalDataMap;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Download my data" (ADR 0037): everything the personal data map finds of the person, nothing of anyone else's, one at
 * a time and one a day, emailed when ready, fetched only by its owner, and deleted after seven days.
 */
@ControlPlaneTest
class ExportTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Autowired
    MutableClock clock;

    @Autowired
    Exports exports;

    @Autowired
    ExportsRetention retention;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID requestAndMake(People.Made person) throws Exception {
        var id = UUID.fromString(body(mvc.perform(post("/v1/me/exports")
                                .header("Authorization", person.user().bearer()))
                        .andExpect(status().isAccepted()))
                .get("id")
                .asString());
        while (exports.makeNext()) {
            // until nothing is waiting
        }
        return id;
    }

    /** The zip's files, by name, as text. */
    private Map<String, String> fetch(People.Made person, UUID exportId) throws Exception {
        var link = body(mvc.perform(get("/v1/me/exports/" + exportId + "/download")
                                .header("Authorization", person.user().bearer()))
                        .andExpect(status().isOk()))
                .get("url")
                .asString();
        byte[] zip;
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(link)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                    .contains("idleo-data-");
            zip = response.body();
        }
        var files = new LinkedHashMap<String, String>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void holdsEverythingTheMapFindsOfThePersonAndNothingOfAnyoneElse() throws Exception {
        var asha = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "asha-" + UUID.randomUUID().toString().substring(0, 8));
        var bina = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "bina-" + UUID.randomUUID().toString().substring(0, 8));

        var files = fetch(asha, requestAndMake(asha));

        assertThat(files.keySet())
                .contains("README.txt")
                .containsAll(PersonalData.GROUPS.stream()
                        .map(group -> group + ".json")
                        .toList());
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (var table : PersonalDataMap.tables()) {
            if (table.exportGroup() == null || table.whose() == null) {
                continue;
            }
            assertThat(PersonalDataMap.rows(named, table, asha.ids()))
                    .as("the fixture gives %s rows in %s", asha.marker(), table.name())
                    .isNotEmpty();
            var part = JSON.readTree(files.get(table.exportGroup() + ".json")).get(table.exportKey());
            assertThat(part)
                    .as("%s's rows are in %s.json's %s", table.name(), table.exportGroup(), table.exportKey())
                    .isNotNull();
            assertThat(part.isNull() || (part.isArray() && part.isEmpty()))
                    .as("%s.json's %s holds %s's rows", table.exportGroup(), table.exportKey(), table.name())
                    .isFalse();
        }
        var everything = String.join("\n", files.values());
        assertThat(everything)
                .contains(asha.marker(), asha.email(), asha.ids().user().toString());
        assertThat(everything)
                .doesNotContain(bina.marker())
                .doesNotContain(bina.email())
                .doesNotContain(bina.ids().user().toString())
                .doesNotContain(bina.ids().sessions().getFirst().toString())
                .doesNotContain(bina.ids().hosts().getFirst().toString())
                .doesNotContain(bina.ids().uploads().getFirst().toString());

        // A link to a file works, for seven days.
        var link = JSON.readTree(files.get("files.json"))
                .get("uploads")
                .get(0)
                .get("files")
                .get(0)
                .get("link")
                .asString();
        assertThat(link).contains("X-Amz-Expires=604800");
        try (var http = HttpClient.newHttpClient()) {
            var file =
                    http.send(HttpRequest.newBuilder(URI.create(link)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(file.body()).isEqualTo(asha.marker());
        }
        // Money in paise beside rupees.
        assertThat(JSON.readTree(files.get("money.json"))
                        .get("ledger")
                        .get(0)
                        .get("amountRupees")
                        .asString())
                .isEqualTo("₹25.00");
        // Told by email and in the app, once.
        assertThat(jdbc.queryForObject(
                        "select count(*) from notifications.notifications where user_id = ? and kind = 'account-and-data'",
                        Integer.class,
                        asha.ids().user()))
                .isOne();
    }

    @Test
    void oneAtATimeOneADayAndOnlyItsOwnerFetchesIt() throws Exception {
        var asha = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "asha-" + UUID.randomUUID().toString().substring(0, 8));
        var bina = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "bina-" + UUID.randomUUID().toString().substring(0, 8));
        mvc.perform(post("/v1/me/exports").header("Authorization", asha.user().bearer()))
                .andExpect(status().isAccepted());
        mvc.perform(post("/v1/me/exports").header("Authorization", asha.user().bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/export-in-progress"));
        while (exports.makeNext()) {
            // until nothing is waiting
        }
        mvc.perform(post("/v1/me/exports").header("Authorization", asha.user().bearer()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/export-too-soon"))
                .andExpect(header().exists("Retry-After"));
        var made = body(mvc.perform(get("/v1/me/exports")
                        .header("Authorization", asha.user().bearer())))
                .get("exports")
                .get(0);
        assertThat(made.get("state").asString()).isEqualTo("ready");
        mvc.perform(get("/v1/me/exports/" + made.get("id").asString() + "/download")
                        .header("Authorization", bina.user().bearer()))
                .andExpect(status().isNotFound());

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        mvc.perform(post("/v1/me/exports").header("Authorization", asha.user().bearer()))
                .andExpect(status().isAccepted());
    }

    @Test
    void isDeletedSevenDaysAfterItsReady() throws Exception {
        var asha = People.make(
                mvc,
                jdbc,
                environment,
                clock,
                "asha-" + UUID.randomUUID().toString().substring(0, 8));
        var id = requestAndMake(asha);
        fetch(asha, id);
        try (var s3 = People.storage(environment)) {
            var bucket = environment.getRequiredProperty("sessions.storage.bucket");
            assertThat(s3.listObjectsV2(request -> request.bucket(bucket).prefix("exports/" + id + "/"))
                            .keyCount())
                    .isOne();

            assertThat(retention.apply(clock.instant().plus(Duration.ofDays(6))))
                    .isZero();
            assertThat(retention.apply(clock.instant().plus(Duration.ofDays(7).plusMinutes(1))))
                    .isOne();

            assertThat(s3.listObjectsV2(request -> request.bucket(bucket).prefix("exports/" + id + "/"))
                            .keyCount())
                    .isZero();
        }
        assertThat(jdbc.queryForObject("select state from privacy.exports where id = ?", String.class, id))
                .isEqualTo("expired");
        mvc.perform(get("/v1/me/exports/" + id + "/download")
                        .header("Authorization", asha.user().bearer()))
                .andExpect(status().isNotFound());
    }
}
