package com.idleo.controlplane.sessions.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestUsers.TestUser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ready uploads for tests: created through the API, PUT to storage over their links, then completed (ADR 0019). */
final class TestUploads {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TestUploads() {}

    /** A ready upload of {@code content} named {@code filename}; its id. */
    static UUID ready(MockMvc mvc, TestUser user, String filename, byte[] content) throws Exception {
        var upload = JSON.readTree(mvc.perform(post("/v1/uploads")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("filename", filename, "sizeBytes", content.length))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var parts = new ArrayList<Map<String, Object>>();
        long partSize = upload.get("partSizeBytes").asLong();
        for (JsonNode part : upload.get("parts")) {
            int number = part.get("number").asInt();
            int from = (int) ((number - 1) * partSize);
            int to = (int) Math.min(content.length, number * partSize);
            var response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(part.get("url").asString()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content, from, to - from))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            parts.add(Map.of(
                    "number",
                    number,
                    "etag",
                    response.headers().firstValue("ETag").orElseThrow()));
        }
        var id = upload.get("id").asString();
        mvc.perform(post("/v1/uploads/" + id + "/complete")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("parts", parts))))
                .andExpect(status().isOk());
        return UUID.fromString(id);
    }

    static UUID ready(MockMvc mvc, TestUser user, String filename, String content) throws Exception {
        return ready(mvc, user, filename, content.getBytes(StandardCharsets.UTF_8));
    }
}
