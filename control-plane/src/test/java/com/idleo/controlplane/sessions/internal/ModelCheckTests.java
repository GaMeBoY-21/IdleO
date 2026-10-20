package com.idleo.controlplane.sessions.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers.TestUser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Fine-tune checks what the renter uploaded when it's booked, not after a GPU has been found (ADR 0022): the model's folder,
 * or a zip of it, must hold a config.json and weights; the data must be a .jsonl or .json file. Each refusal is {@code
 * input-not-usable} (422) in the words the catalog gives, the same the booking screen shows before Book.
 */
@ControlPlaneTest
class ModelCheckTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NO_CONFIG =
            "This folder has no config.json, so it isn't a Hugging Face model. Upload the model's folder, the one with config.json and the weights.";
    private static final String NO_WEIGHTS =
            "This folder has no weights file (.safetensors or .bin). Upload the model's folder, the one with config.json and the weights.";
    private static final String NOT_JSONL = "This file isn't JSONL. Use a .jsonl file with one example per line.";

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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(List<String> names) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(bytes("x"));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private UUID folder(TestUser renter, Map<String, byte[]> files) throws Exception {
        return TestUploads.readyFolder(mvc, renter, "model", new LinkedHashMap<>(files));
    }

    private ResultActions book(TestUser renter, UUID model, UUID data) throws Exception {
        var body = Map.of(
                "templateId",
                "fine-tune-simulated",
                "maxPricePaisePerMin",
                5,
                "maxSpendPaise",
                10_000,
                "inputs",
                Map.of("model", Map.of("uploadId", model), "data", Map.of("uploadId", data)));
        return mvc.perform(post("/v1/sessions")
                .header("Authorization", renter.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private void refused(ResultActions result, String words) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/input-not-usable"))
                .andExpect(jsonPath("$.detail").value(words))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.model"));
    }

    @Test
    void aPdfIsNotAModel() throws Exception {
        var renter = bookings.renter();
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"text\": \"a\"}\n");
        var pdf = folder(renter, Map.of("paper.pdf", bytes("%PDF-1.7")));

        refused(book(renter, pdf, data), NO_CONFIG);
    }

    @Test
    void aFolderWithoutConfigJsonOrWithoutWeightsIsNotAModel() throws Exception {
        var renter = bookings.renter();
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"text\": \"a\"}\n");

        refused(
                book(renter, folder(renter, Map.of("tokenizer.json", bytes("{}"), "w.safetensors", new byte[8])), data),
                NO_CONFIG);
        refused(
                book(renter, folder(renter, Map.of("config.json", bytes("{}"), "tokenizer.json", bytes("{}"))), data),
                NO_WEIGHTS);
        // config.json too deep to be the model's own is not its config.
        refused(
                book(
                        renter,
                        folder(renter, Map.of("a/b/config.json", bytes("{}"), "a/b/w.safetensors", new byte[8])),
                        data),
                NO_CONFIG);
    }

    @Test
    void aRealShapedFolderIsAccepted() throws Exception {
        var renter = bookings.renter();
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"text\": \"a\"}\n");
        var flat = folder(
                renter,
                Map.of(
                        "config.json",
                        bytes("{}"),
                        "tokenizer.json",
                        bytes("{}"),
                        "model-00001-of-00002.safetensors",
                        new byte[8],
                        "model-00002-of-00002.safetensors",
                        new byte[8]));
        var inside = folder(renter, Map.of("my-llm/config.json", bytes("{}"), "my-llm/pytorch_model.bin", new byte[8]));

        book(renter, flat, data).andExpect(status().isCreated());
        book(renter, inside, data).andExpect(status().isCreated());
    }

    @Test
    void aZipIsCheckedByWhatItHoldsAndNeverRead_wholeFromStorage() throws Exception {
        var renter = bookings.renter();
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"text\": \"a\"}\n");

        var good = TestUploads.ready(
                mvc, renter, "model.zip", zip(List.of("llm/", "llm/config.json", "llm/w.safetensors")));
        var noConfig = TestUploads.ready(mvc, renter, "model.zip", zip(List.of("llm/w.safetensors")));
        var noWeights = TestUploads.ready(mvc, renter, "model.zip", zip(List.of("llm/config.json")));

        book(renter, good, data).andExpect(status().isCreated());
        refused(book(renter, noConfig, data), NO_CONFIG);
        refused(book(renter, noWeights, data), NO_WEIGHTS);
    }

    @Test
    void aZipThatCantBeReadHasItsOwnWords() throws Exception {
        var renter = bookings.renter();
        var data = TestUploads.ready(mvc, renter, "train.jsonl", "{\"text\": \"a\"}\n");
        var broken = TestUploads.ready(
                mvc, renter, "model.zip", bytes("this is not a zip file at all, it is text".repeat(3)));

        refused(book(renter, broken, data), InputContent.UNREADABLE_ZIP);
    }

    @Test
    void theDataMustBeAJsonlOrJsonFile() throws Exception {
        var renter = bookings.renter();
        var model = folder(renter, Map.of("config.json", bytes("{}"), "w.safetensors", new byte[8]));
        var pdf = TestUploads.ready(mvc, renter, "notes.pdf", "%PDF-1.7");
        var json = TestUploads.ready(mvc, renter, "train.JSON", "[]");

        book(renter, model, pdf)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/input-not-usable"))
                .andExpect(jsonPath("$.detail").value(NOT_JSONL))
                .andExpect(jsonPath("$.errors[0].field").value("inputs.data"));
        book(renter, model, json).andExpect(status().isCreated());
    }

    @Test
    void aLinkIsLeftToTheJobToCheck() throws Exception {
        var renter = bookings.renter();
        var body = Map.of(
                "templateId",
                "fine-tune-simulated",
                "maxPricePaisePerMin",
                5,
                "maxSpendPaise",
                10_000,
                "inputs",
                Map.of(
                        "model", Map.of("url", "https://huggingface.co/some/model"),
                        "data", Map.of("url", "https://example.com/train.jsonl")));
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
