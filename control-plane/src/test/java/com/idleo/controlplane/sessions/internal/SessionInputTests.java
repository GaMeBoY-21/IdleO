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
 * Sessions take parameters, preset answers and named inputs (ADR 0019, ADR 0022): checked against the template,
 * stored resolved, and given to the job as its environment, with each uploaded input named in the StartSession, in its
 * slot, and readable through its read grant.
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
        if (input != null) {
            body.put("inputs", Map.of("file", input));
        }
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
                .andExpect(jsonPath("$.inputs.file.uploadId").value(upload.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var sessionId = UUID.fromString(booked.get("id").asString());

        var offer = host.awaitOffer(TestBookings.WAIT);
        assertThat(offer.getSpec().getEnvMap())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SESSION_PARAM_GREETING", "hello",
                        "SESSION_PARAM_REPEAT", "1",
                        "SESSION_INPUT_FILE_DIR", "/work/input/file/"));
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
        var envelope = host.awaitStart(TestBookings.WAIT);
        host.ack(envelope);
        var start = envelope.getStartSession();

        assertThat(start.getSpec().getEnvMap()).isEqualTo(offer.getSpec().getEnvMap());
        assertThat(start.hasInput())
                .as("the deprecated single input is never sent")
                .isFalse();
        assertThat(start.getInputsList()).hasSize(1);
        assertThat(start.getInputs(0).getSlot()).isEqualTo("file");
        assertThat(start.getInputs(0).getPrefix()).isEqualTo("uploads/" + upload + "/files/");
        assertThat(start.getInputs(0).getName()).isEqualTo("my_data.txt");
        assertThat(start.getInputs(0).getSizeBytes()).isEqualTo(11);
        var readGrant = start.getCredentials().getReadGrant();
        assertThat(readGrant.getPrefixesList()).containsExactly("uploads/" + upload + "/");
        try (var s3 = TestS3.as(readGrant)) {
            assertThat(TestS3.get(s3, "uploads/" + upload + "/files/my_data.txt"))
                    .isEqualTo("hello world");
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
                .andExpect(jsonPath("$.inputs.file.url").value("https://example.com/d.txt"));

        var offer = host.awaitOffer(TestBookings.WAIT);
        assertThat(offer.getSpec().getEnvMap())
                .containsEntry("SESSION_INPUT_FILE_URL", "https://example.com/d.txt")
                .containsEntry("SESSION_PARAM_REPEAT", "3")
                .doesNotContainKey("SESSION_INPUT_FILE_DIR");
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
        var start = host.awaitStart(TestBookings.WAIT).getStartSession();
        assertThat(start.getInputsList()).as("the agent never fetches a URL").isEmpty();
        assertThat(start.getCredentials().hasReadGrant()).isFalse();
    }

    @Test
    void theInputMustFitWhatTheTemplateTakes() throws Exception {
        var renter = bookings.renter();
        var upload = TestUploads.ready(mvc, renter, "a.txt", "a");

        book(
                        renter,
                        "smoke-in",
                        Map.of("templateId", "cuda-smoke-test", "inputs", Map.of("file", Map.of("uploadId", upload))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.file"));
        book(
                        renter,
                        "code-link",
                        Map.of(
                                "templateId",
                                "run-code-simulated",
                                "inputs",
                                Map.of("code", Map.of("url", "https://example.com/code.zip"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-not-accepted"))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.code"));
        book(renter, "echo-none", echo(Map.of("greeting", "hi"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-required"))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.file"));
        book(
                        renter,
                        "echo-both",
                        echo(Map.of("greeting", "hi"), Map.of("uploadId", upload, "url", "https://x.example/a")))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.file"));
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
                    .andExpect(jsonPath("$.errors[0].field").value("inputs.file.url"));
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

        var pending = TestUploads.created(mvc, renter, "p.txt", Map.of("p.txt", 10L))
                .at("/upload/id")
                .asString();
        book(renter, "pending", echo(Map.of("greeting", "hi"), Map.of("uploadId", pending)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-not-ready"));

        var big = TestUploads.ready(mvc, renter, "big.bin", "x");
        jdbc.update("update sessions.uploads set size_bytes = ? where id = ?", 513L * 1024 * 1024, big);
        book(renter, "big", echo(Map.of("greeting", "hi"), Map.of("uploadId", big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-too-large"))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.file"));
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
                "update sessions.session_inputs set upload_id = ? where session_id = ?",
                largeUpload,
                JSON.readTree(book(renter, "large", echo(Map.of("greeting", "hi"), Map.of("uploadId", smallUpload)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .get("id")
                        .asString()
                        .transform(UUID::fromString));
        var largeSession = jdbc.queryForObject(
                "select session_id from sessions.session_inputs where upload_id = ?", UUID.class, largeUpload);
        large.reply(large.awaitOffer(TestBookings.WAIT).getOfferId(), true, TestBookings.ACCEPT);

        var failed = bookings.awaitState(renter, smallSession, "FAILED");
        var events = failed.get("events");
        assertThat(events.get(events.size() - 1).get("reason").asString()).isEqualTo("start_timeout");
        Thread.sleep(Duration.ofSeconds(1));
        JsonNode stillProvisioning = bookings.session(renter, largeSession);
        assertThat(stillProvisioning.get("state").asString()).isEqualTo("PROVISIONING");
    }

    private static Map<String, Object> fineTune(Map<String, Object> extra) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("templateId", "fine-tune-simulated");
        body.putAll(extra);
        return body;
    }

    @Test
    void eachNamedInputReachesTheJobInItsOwnFolderOrAsItsOwnLink() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();
        var model = TestUploads.ready(mvc, renter, "model.safetensors", "weights");

        book(
                        renter,
                        "two-inputs",
                        fineTune(Map.of(
                                "inputs",
                                Map.of(
                                        "model", Map.of("uploadId", model),
                                        "data", Map.of("url", "https://example.com/train.jsonl")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inputs.model.uploadId").value(model.toString()))
                .andExpect(jsonPath("$.inputs.data.url").value("https://example.com/train.jsonl"));

        var offer = host.awaitOffer(TestBookings.WAIT);
        assertThat(offer.getSpec().getEnvMap())
                .containsEntry("SESSION_INPUT_MODEL_DIR", "/work/input/model/")
                .containsEntry("SESSION_INPUT_DATA_URL", "https://example.com/train.jsonl");
        host.reply(offer.getOfferId(), true, TestBookings.ACCEPT);
        var start = host.awaitStart(TestBookings.WAIT).getStartSession();
        assertThat(start.getInputsList()).singleElement().satisfies(input -> {
            assertThat(input.getSlot()).isEqualTo("model");
            assertThat(input.getName()).isEqualTo("model.safetensors");
        });
        assertThat(start.getCredentials().getReadGrant().getPrefixesList()).containsExactly("uploads/" + model + "/");
    }

    @Test
    void aFolderReachesTheAgentAsOneInputRefAFileWithItsPathKept() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();
        var folder = new java.util.LinkedHashMap<String, byte[]>();
        folder.put("config.json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        folder.put("tokenizer.json", "{\"v\": 1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        folder.put("weights/model-00001-of-00001.safetensors", new byte[64]);
        var model = TestUploads.readyFolder(mvc, renter, "My model", folder);
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"x\": 1}\n");

        book(
                        renter,
                        "folder",
                        fineTune(Map.of(
                                "inputs",
                                Map.of("model", Map.of("uploadId", model), "data", Map.of("uploadId", data)))))
                .andExpect(status().isCreated());

        host.reply(host.awaitOffer(TestBookings.WAIT).getOfferId(), true, TestBookings.ACCEPT);
        var start = host.awaitStart(TestBookings.WAIT).getStartSession();
        assertThat(start.getInputsList())
                .extracting(input -> input.getSlot() + ":" + input.getName() + ":" + input.getSizeBytes())
                .containsExactlyInAnyOrder(
                        "model:config.json:2",
                        "model:tokenizer.json:8",
                        "model:weights/model-00001-of-00001.safetensors:64",
                        "data:train.jsonl:9");
        assertThat(start.getInputsList()).allMatch(input -> input.getPrefix().endsWith("/files/"));
        try (var s3 = TestS3.as(start.getCredentials().getReadGrant())) {
            var weights = start.getInputsList().stream()
                    .filter(input -> input.getName().startsWith("weights/"))
                    .findFirst()
                    .orElseThrow();
            assertThat(TestS3.get(s3, weights.getPrefix() + weights.getName())).hasSize(64);
        }
    }

    @Test
    void aFileInputTakesOneFileNotAFolder() throws Exception {
        var renter = bookings.renter();
        var two = TestUploads.readyFolder(
                mvc, renter, "two", Map.of("a.jsonl", new byte[] {1}, "b.jsonl", new byte[] {2}));

        book(
                        renter,
                        "two-files",
                        fineTune(Map.of(
                                "inputs",
                                Map.of(
                                        "model", Map.of("url", "https://huggingface.co/me/model"),
                                        "data", Map.of("uploadId", two)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-not-accepted"))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.data"));
    }

    @Test
    void theGpuMemoryComesFromTheSizeOfTheRentersModel() throws Exception {
        var renter = bookings.renter();
        var model = TestUploads.ready(mvc, renter, "model.safetensors", "weights");
        // As if it were 14.2 GiB: 2,048 + 700 x 14.2 = 11,988 MB, a 12 GB GPU.
        jdbc.update("update sessions.uploads set size_bytes = ? where id = ?", (long) (14.2 * (1L << 30)), model);
        var inputs =
                Map.of("model", Map.of("uploadId", model), "data", Map.of("url", "https://example.com/train.jsonl"));

        mvc.perform(post("/v1/session-quotes")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                fineTune(Map.of("inputs", inputs, "maxPricePaisePerMin", 5, "maxSpendPaise", 10_000)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vramNeededMb").value(12_000));
        book(renter, "too-little", fineTune(Map.of("inputs", inputs, "vramNeededMb", 8_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("vramNeededMb"))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value("must be at least the 12000 MB the uploaded model needs."));
        book(renter, "sized", fineTune(Map.of("inputs", inputs)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vramNeededMb").value(12_000));
        book(renter, "more", fineTune(Map.of("inputs", inputs, "vramNeededMb", 16_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vramNeededMb").value(16_000));
        book(
                        renter,
                        "linked",
                        fineTune(Map.of(
                                "inputs",
                                Map.of(
                                        "model", Map.of("url", "https://huggingface.co/me/model"),
                                        "data", Map.of("url", "https://example.com/train.jsonl")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vramNeededMb").value(6_144));
    }

    @Test
    void aHostWithTooLittleDiskForTheSessionsFilesIsNeverOfferedIt() throws Exception {
        var host = bookings.host();
        var renter = bookings.renter();
        var upload = TestUploads.ready(mvc, renter, "big.bin", "x");
        // As if it were 300 MiB: with the 1,024 MB scratch disk and a 10% margin, the session needs 1,457 MB.
        jdbc.update("update sessions.uploads set size_bytes = ? where id = ?", 300L << 20, upload);
        host.freeDiskMb().set(1_456);
        Thread.sleep(Duration.ofSeconds(1));

        var cramped = UUID.fromString(JSON.readTree(
                        book(renter, "cramped", echo(Map.of("greeting", "hi"), Map.of("uploadId", upload)))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("id")
                .asString());
        bookings.awaitState(renter, cramped, "NO_HOST_FREE");

        host.freeDiskMb().set(1_457);
        Thread.sleep(Duration.ofSeconds(1));
        book(renter, "roomy", echo(Map.of("greeting", "hi"), Map.of("uploadId", upload)))
                .andExpect(status().isCreated());
        assertThat(host.awaitOffer(TestBookings.WAIT).getSpec().getScratchMb()).isEqualTo(1_024);
    }

    @Test
    void everyMissingRequiredInputIsNamedAndAnUnknownOneIsRefused() throws Exception {
        var renter = bookings.renter();

        book(renter, "none", fineTune(Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/input-required"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.contains("inputs.model", "inputs.data")));
        book(renter, "unknown", fineTune(Map.of("inputs", Map.of("weights", Map.of("url", "https://example.com/w")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("inputs.weights"));
    }

    @Test
    void aPresetSetsItsParametersAndIsKeptForRunAgain() throws Exception {
        var renter = bookings.renter();
        var links = Map.of(
                "model", Map.of("url", "https://huggingface.co/me/model"),
                "data", Map.of("url", "https://example.com/train.jsonl"));

        book(renter, "default", fineTune(Map.of("inputs", links)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.presets.thoroughness").value("standard"))
                .andExpect(jsonPath("$.params.epochs").value(3));
        book(renter, "quick", fineTune(Map.of("inputs", links, "presets", Map.of("thoroughness", "quick"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.presets.thoroughness").value("quick"))
                .andExpect(jsonPath("$.params.epochs").value(1));
        book(
                        renter,
                        "advanced",
                        fineTune(Map.of(
                                "inputs",
                                links,
                                "presets",
                                Map.of("thoroughness", "quick"),
                                "params",
                                Map.of("epochs", 4))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.params.epochs").value(4));
        book(renter, "slow", fineTune(Map.of("inputs", links, "presets", Map.of("thoroughness", "slow"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("questions.thoroughness"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be one of quick, standard, thorough."));
        book(renter, "speed", fineTune(Map.of("inputs", links, "presets", Map.of("speed", "fast"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("questions.speed"));
        book(renter, "quick", fineTune(Map.of("inputs", links, "presets", Map.of("thoroughness", "thorough"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
    }
}
