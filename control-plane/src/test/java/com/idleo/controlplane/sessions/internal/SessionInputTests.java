package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Sessions take parameters and an input (ADR 0019): checked against the template, stored resolved, and given to
 * the job as its environment, with an uploaded input named in the StartSession and readable through its read grant.
 */
@ControlPlaneTest
class SessionInputTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private TestBookings bookings;

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        bookings.close();
    }

    private ResultActions book(TestUser renter, String key, Map<String, Object> body) throws Exception {
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("maxPricePaisePerMin", 5);
        request.put("maxSpendPaise", 10_000);
        request.putAll(body);
        return mvc.perform(post("/v1/sessions")
                .header("Authorization", renter.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(request)));
    }

    private static Map<String, Object> echo(Object params, Object input) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("templateId", "echo-input");
        body.put("params", params);
        body.put("input", input);
        return body;
    }

    @Test
    void aSessionWithParametersAndAnUploadGivesTheJobItsEnvironmentAndItsInput() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();
        var upload = TestUploads.ready(mvc, renter, "my data.txt", "hello world");

        var booked = JSON.readTree(book(renter, "echo-1", echo(Map.of("greeting", "hello"), Map.of("uploadId", upload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.params.greeting").value("hello"))
                .andExpect(jsonPath("$.params.repeat").value(1))
                .andExpect(jsonPath("$.input.uploadId").value(upload.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var sessionId = UUID.fromString(booked.get("id").asString());

        var offer = host.awaitOffer(TestBookings.WAIT);
        assertThat(offer.getSpec().getEnvMap())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SESSION_PARAM_GREETING", "hello",
                        "SESSION_PARAM_REPEAT", "1",
                        "SESSION_INPUT_FILE", "/work/input/my_data.txt"));
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
        var envelope = host.awaitStart(TestBookings.WAIT);
        host.ack(envelope);
        var start = envelope.getStartSession();

        assertThat(start.getSpec().getEnvMap()).isEqualTo(offer.getSpec().getEnvMap());
        assertThat(start.getInput().getPrefix()).isEqualTo("uploads/" + upload + "/");
        assertThat(start.getInput().getName()).isEqualTo("my_data.txt");
        assertThat(start.getInput().getSizeBytes()).isEqualTo(11);
        var readGrant = start.getCredentials().getReadGrant();
        assertThat(readGrant.getPrefixesList()).containsExactly("uploads/" + upload + "/");
        try (var s3 = TestS3.as(readGrant)) {
            assertThat(TestS3.get(s3, "uploads/" + upload + "/my_data.txt")).isEqualTo("hello world");
        }
        assertThat(jdbc.queryForObject(
                        "select params::text from sessions.sessions where id = ?", String.class, sessionId))
                .contains("\"greeting\": \"hello\"");
    }

    @Test
    void aUrlInputReachesTheJobOnlyAsAnEnvironmentVariable() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();

        book(
                        renter,
                        "echo-url",
                        echo(Map.of("greeting", "hi", "repeat", 3), Map.of("url", "https://example.com/d.txt")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.input.url").value("https://example.com/d.txt"));

        var offer = host.awaitOffer(TestBookings.WAIT);
        assertThat(offer.getSpec().getEnvMap())
                .containsEntry("SESSION_INPUT_URL", "https://example.com/d.txt")
                .containsEntry("SESSION_PARAM_REPEAT", "3")
                .doesNotContainKey("SESSION_INPUT_FILE");
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
        var start = host.awaitStart(TestBookings.WAIT).getStartSession();
        assertThat(start.hasInput()).as("the agent never fetches a URL").isFalse();
        assertThat(start.getCredentials().hasReadGrant()).isFalse();
    }

    @Test
    void theInputMustFitWhatTheTemplateTakes() throws Exception {
        var renter = bookings.renter();
        var upload = TestUploads.ready(mvc, renter, "a.txt", "a");

        book(renter, "smoke-in", Map.of("templateId", "cuda-smoke-test", "input", Map.of("uploadId", upload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-not-accepted"));
        book(renter, "echo-none", echo(Map.of("greeting", "hi"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-required"));
        book(
                        renter,
                        "echo-both",
                        echo(Map.of("greeting", "hi"), Map.of("uploadId", upload, "url", "https://x.example/a")))
                .andExpect(jsonPath("$.errors[0].field").value("input"));
        for (var url : new String[] {
            "http://example.com/a",
            "https://user:pass@example.com/a",
            "https://localhost/a",
            "https://127.0.0.1/a",
            "https://10.0.0.5/a",
            "https://192.168.1.1/a",
            "https://169.254.169.254/latest/meta-data",
            "https://100.100.100.100/a",
            "https://[::1]/a",
            "https://[fd00::1]/a",
            "https://example.com/" + "a".repeat(2_048)
        }) {
            book(renter, UUID.randomUUID().toString(), echo(Map.of("greeting", "hi"), Map.of("url", url)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("input.url"));
        }
    }

    @Test
    void anUploadMustBeTheRentersOwnReadyAndNotTooLarge() throws Exception {
        var renter = bookings.renter();
        var other = TestUsers.signUp(mvc);
        var theirs = TestUploads.ready(mvc, other, "theirs.txt", "x");
        book(renter, "theirs", echo(Map.of("greeting", "hi"), Map.of("uploadId", theirs)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));

        var pending = JSON.readTree(mvc.perform(post("/v1/uploads")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"filename\": \"p.txt\", \"sizeBytes\": 10}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        book(renter, "pending", echo(Map.of("greeting", "hi"), Map.of("uploadId", pending)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-not-ready"));

        var big = TestUploads.ready(mvc, renter, "big.bin", "x");
        jdbc.update("update sessions.uploads set size_bytes = ? where id = ?", 513L * 1024 * 1024, big);
        book(renter, "big", echo(Map.of("greeting", "hi"), Map.of("uploadId", big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-too-large"));
    }

    @Test
    void parametersAreCheckedAndARetryWithADifferentInputIsRefused() throws Exception {
        var renter = bookings.renter();
        var first = TestUploads.ready(mvc, renter, "1.txt", "1");
        var second = TestUploads.ready(mvc, renter, "2.txt", "2");

        book(
                        renter,
                        "bad-params",
                        echo(Map.of("greeting", "hi", "repeat", 9, "colour", "red"), Map.of("uploadId", first)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("params.colour", "params.repeat")));

        book(renter, "retry", echo(Map.of("greeting", "hi"), Map.of("uploadId", first)))
                .andExpect(status().isCreated());
        book(renter, "retry", echo(Map.of("greeting", "hi", "repeat", 1), Map.of("uploadId", first)))
                .andExpect(status().isOk());
        book(renter, "retry", echo(Map.of("greeting", "hi"), Map.of("uploadId", second)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        book(renter, "retry", echo(Map.of("greeting", "hello"), Map.of("uploadId", first)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theStartTimeoutGrowsWithTheInput() throws Exception {
        var renter = bookings.renter();
        var small = bookings.host();
        var smallUpload = TestUploads.ready(mvc, renter, "small.txt", "s");
        var smallSession = UUID.fromString(JSON.readTree(
                        book(renter, "small", echo(Map.of("greeting", "hi"), Map.of("uploadId", smallUpload)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("id")
                .asString());
        small.reply(small.awaitOffer(TestBookings.WAIT).getOfferId(), true, TestBookings.ACCEPT);

        var large = bookings.host();
        var largeUpload = TestUploads.ready(mvc, renter, "large.bin", "l");
        // As if it were a gigabyte: at the staging rate, minutes more to download.
        jdbc.update("update sessions.uploads set size_bytes = ? where id = ?", 1L << 30, largeUpload);
        jdbc.update(
                "update sessions.sessions set input_upload_id = ? where id = ?",
                largeUpload,
                JSON.readTree(book(renter, "large", echo(Map.of("greeting", "hi"), Map.of("uploadId", smallUpload)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .get("id")
                        .asString()
                        .transform(UUID::fromString));
        var largeSession = jdbc.queryForObject(
                "select id from sessions.sessions where input_upload_id = ?", UUID.class, largeUpload);
        large.reply(large.awaitOffer(TestBookings.WAIT).getOfferId(), true, TestBookings.ACCEPT);

        var failed = bookings.awaitState(renter, smallSession, "FAILED");
        var events = failed.get("events");
        assertThat(events.get(events.size() - 1).get("reason").asString()).isEqualTo("start_timeout");
        Thread.sleep(Duration.ofSeconds(1));
        JsonNode stillProvisioning = bookings.session(renter, largeSession);
        assertThat(stillProvisioning.get("state").asString()).isEqualTo("PROVISIONING");
    }
}
