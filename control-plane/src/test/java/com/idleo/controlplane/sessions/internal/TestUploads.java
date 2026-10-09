package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestUsers.TestUser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uploads as a client makes them (ADR 0019, ADR 0022): created with their files, each file's parts PUT straight to
 * storage over links asked for per file, then completed.
 */
final class TestUploads {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TestUploads() {}

    /** {@code POST /v1/uploads} of {@code files}, each path with its size, in order. */
    static ResultActions create(MockMvc mvc, TestUser user, String key, String name, Map<String, Long> files)
            throws Exception {
        var listed = files.entrySet().stream()
                .map(file -> Map.<String, Object>of("path", file.getKey(), "sizeBytes", file.getValue()))
                .toList();
        return mvc.perform(post("/v1/uploads")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", name, "files", listed))));
    }

    /** A new pending upload of {@code files}; its body. */
    static JsonNode created(MockMvc mvc, TestUser user, String name, Map<String, Long> files) throws Exception {
        return JSON.readTree(create(mvc, user, UUID.randomUUID().toString(), name, files)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** {@code POST /v1/uploads/{id}/part-urls} for one file. */
    static ResultActions links(MockMvc mvc, TestUser user, String id, String path, List<Integer> numbers)
            throws Exception {
        return mvc.perform(post("/v1/uploads/" + id + "/part-urls")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("path", path, "partNumbers", numbers))));
    }

    /** PUTs each slice of {@code content} as a part of the file at {@code path}, as a browser would; the ETags. */
    static List<Map<String, Object>> putFile(MockMvc mvc, TestUser user, JsonNode upload, String path, byte[] content)
            throws Exception {
        var id = upload.at("/upload/id").asString();
        long partSize = upload.at("/upload/partSizeBytes").asLong();
        int count = (int) ((content.length + partSize - 1) / partSize);
        var parts = new ArrayList<Map<String, Object>>();
        if (count == 0) {
            return parts;
        }
        var links = JSON.readTree(links(
                        mvc,
                        user,
                        id,
                        path,
                        IntStream.rangeClosed(1, count).boxed().toList())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        for (var part : links.get("parts")) {
            int number = part.get("number").asInt();
            int from = (int) ((number - 1) * partSize);
            int to = (int) Math.min(content.length, number * partSize);
            var response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(part.get("url").asString()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content, from, to - from))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            parts.add(Map.of(
                    "number",
                    number,
                    "etag",
                    response.headers().firstValue("ETag").orElseThrow()));
        }
        return parts;
    }

    /** {@code POST /v1/uploads/{id}/complete} for each file named, with its parts. */
    static ResultActions complete(MockMvc mvc, TestUser user, String id, Map<String, List<Map<String, Object>>> files)
            throws Exception {
        var listed = files.entrySet().stream()
                .map(file -> Map.<String, Object>of("path", file.getKey(), "parts", file.getValue()))
                .toList();
        return mvc.perform(post("/v1/uploads/" + id + "/complete")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("files", listed))));
    }

    /** A ready upload named {@code name} of {@code files}, each path with its contents; its id. */
    static UUID readyFolder(MockMvc mvc, TestUser user, String name, Map<String, byte[]> files) throws Exception {
        var sizes = new LinkedHashMap<String, Long>();
        files.forEach((path, content) -> sizes.put(path, (long) content.length));
        var upload = created(mvc, user, name, sizes);
        var parts = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var file : files.entrySet()) {
            parts.put(file.getKey(), putFile(mvc, user, upload, file.getKey(), file.getValue()));
        }
        var id = upload.at("/upload/id").asString();
        complete(mvc, user, id, parts).andExpect(status().isOk());
        return UUID.fromString(id);
    }

    /** A ready upload of one file, {@code content} named {@code filename}; its id. */
    static UUID ready(MockMvc mvc, TestUser user, String filename, byte[] content) throws Exception {
        return readyFolder(mvc, user, filename, Map.of(filename, content));
    }

    static UUID ready(MockMvc mvc, TestUser user, String filename, String content) throws Exception {
        return ready(mvc, user, filename, content.getBytes(StandardCharsets.UTF_8));
    }
}
