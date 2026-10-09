package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
 * Uploads (ADR 0019) against real MinIO: the client PUTs each part straight to storage over its pre-signed link,
 * then completes the upload with the ETags; the control plane never carries the bytes.
 */
@ControlPlaneTest
class UploadApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MIB = 1024 * 1024;
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionStorage storage;

    @Autowired
    UploadExpiry expiry;

    @Autowired
    UploadService uploads;

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

    private static byte[] randomBytes(int size, long seed) {
        var bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private ResultActions create(TestUser user, String key, String filename, long size) throws Exception {
        return mvc.perform(post("/v1/uploads")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("filename", filename, "sizeBytes", size))));
    }

    private JsonNode created(TestUser user, String filename, long size) throws Exception {
        return JSON.readTree(create(user, UUID.randomUUID().toString(), filename, size)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** PUTs each slice of {@code content} to its part's link, as a browser or the CLI would; the ETags. */
    private static List<java.util.Map<String, Object>> putParts(JsonNode upload, byte[] content) throws Exception {
        long partSize = upload.get("partSizeBytes").asLong();
        var parts = new ArrayList<java.util.Map<String, Object>>();
        for (var part : upload.get("parts")) {
            int number = part.get("number").asInt();
            int from = (int) ((number - 1) * partSize);
            int to = (int) Math.min(content.length, number * partSize);
            var response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(part.get("url").asString()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content, from, to - from))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            parts.add(java.util.Map.of(
                    "number",
                    number,
                    "etag",
                    response.headers().firstValue("ETag").orElseThrow()));
        }
        return parts;
    }

    private ResultActions complete(TestUser user, String id, List<java.util.Map<String, Object>> parts)
            throws Exception {
        return mvc.perform(post("/v1/uploads/" + id + "/complete")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("parts", parts))));
    }

    private String objectKey(String id) {
        return jdbc.queryForObject("select object_key from sessions.uploads where id = ?::uuid", String.class, id);
    }

    @Test
    void aFileUploadsInPartsOverPreSignedLinksAndBecomesReady() throws Exception {
        var user = TestUsers.signUp(mvc);
        var content = randomBytes(11 * MIB, 1);

        var upload = created(user, "train data (v2).jsonl", content.length);

        assertThat(upload.get("status").asString()).isEqualTo("pending");
        assertThat(upload.get("partSizeBytes").asLong()).isEqualTo(5L * MIB);
        assertThat(upload.get("partCount").asInt()).isEqualTo(3);
        assertThat(upload.get("parts")).hasSize(3);
        assertThat(upload.get("urlsExpireAt").isNull()).isFalse();
        var id = upload.get("id").asString();
        assertThat(objectKey(id)).isEqualTo("uploads/" + id + "/train_data__v2_.jsonl");

        var parts = putParts(upload, content);
        var ready = complete(user, id, parts)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.filename").value("train data (v2).jsonl"))
                .andReturn();
        assertThat(JSON.readTree(ready.getResponse().getContentAsString())
                        .get("completedAt")
                        .isNull())
                .isFalse();
        assertThat(storage.size(objectKey(id))).contains((long) content.length);
        assertThat(storage.read(objectKey(id)).orElseThrow()).isEqualTo(content);

        complete(user, id, parts)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"));
        mvc.perform(get("/v1/uploads/" + id).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.status").value("ready"));
        mvc.perform(get("/v1/uploads").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.uploads[0].id").value(id));
    }

    @Test
    void aOnePartUploadIsStillMultipart() throws Exception {
        var user = TestUsers.signUp(mvc);
        var content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload = created(user, "hello.txt", content.length);

        assertThat(upload.get("parts")).hasSize(1);
        complete(user, upload.get("id").asString(), putParts(upload, content))
                .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void theWrongSizeFailsTheUploadAndDeletesItsObject() throws Exception {
        var user = TestUsers.signUp(mvc);
        var upload = created(user, "short.bin", 6L * MIB);
        var id = upload.get("id").asString();
        // Every part uploads, but the last carries half what was declared.
        var parts = putParts(upload, randomBytes(5 * MIB + MIB / 2, 2));

        complete(user, id, parts)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));

        mvc.perform(get("/v1/uploads/" + id).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.status").value("failed"));
        assertThat(storage.size(objectKey(id))).isEmpty();
        complete(user, id, parts).andExpect(jsonPath("$.type").value("/problems/upload-not-ready"));
    }

    @Test
    void aMissingPartOrAWrongEtagIsRefusedAndTheUploadStaysPending() throws Exception {
        var user = TestUsers.signUp(mvc);
        var content = randomBytes(6 * MIB, 3);
        var upload = created(user, "two.bin", content.length);
        var id = upload.get("id").asString();
        var parts = putParts(upload, content);

        complete(user, id, parts.subList(0, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));
        complete(user, id, List.of(parts.get(0), java.util.Map.of("number", 2, "etag", "\"0000\"")))
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));
        mvc.perform(get("/v1/uploads/" + id).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.status").value("pending"));

        complete(user, id, parts).andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void freshPartLinksAreIssuedForAPendingUpload() throws Exception {
        var user = TestUsers.signUp(mvc);
        var upload = created(user, "slow.bin", 11L * MIB);
        var id = upload.get("id").asString();

        mvc.perform(post("/v1/uploads/" + id + "/part-urls")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partNumbers\": [2, 3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts[0].number").value(2))
                .andExpect(jsonPath("$.parts[1].number").value(3))
                .andExpect(jsonPath("$.urlsExpireAt").isNotEmpty());
        mvc.perform(post("/v1/uploads/" + id + "/part-urls")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partNumbers\": [4]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("partNumbers"));
    }

    @Test
    void theSameKeyRepeatsTheUploadWithFreshLinksAndADifferentBodyIsRefused() throws Exception {
        var user = TestUsers.signUp(mvc);
        var first = JSON.readTree(create(user, "upload-1", "a.bin", 100)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        create(user, "upload-1", "a.bin", 100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asString()))
                .andExpect(jsonPath("$.parts[0].url").isNotEmpty());
        create(user, "upload-1", "a.bin", 101)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        create(user, "\"has space\"", "a.bin", 100)
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-invalid"));
    }

    @Test
    void badNamesAndSizesAreRefused() throws Exception {
        var user = TestUsers.signUp(mvc);
        for (var name : List.of("..", ".", "a/b.txt", "a\\b.txt", "nul\u0000.txt", "x".repeat(256))) {
            create(user, UUID.randomUUID().toString(), name, 10)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("filename"));
        }
        create(user, UUID.randomUUID().toString(), "big.bin", 21L * 1024 * MIB)
                .andExpect(jsonPath("$.errors[0].field").value("sizeBytes"));
        create(user, UUID.randomUUID().toString(), "empty.bin", 0)
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
    }

    @Test
    void pendingAndReadyUploadsCountAgainstTheQuota() throws Exception {
        var user = TestUsers.signUp(mvc);
        created(user, "first.bin", 40L * MIB);

        create(user, UUID.randomUUID().toString(), "second.bin", 30L * MIB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-quota-exceeded"));
        created(user, "small.bin", 20L * MIB);
    }

    @Test
    void anotherRenterSeesNoneOfIt() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var other = TestUsers.signUp(mvc);
        var id = created(owner, "mine.bin", 10).get("id").asString();

        mvc.perform(get("/v1/uploads/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        complete(other, id, List.of(java.util.Map.of("number", 1, "etag", "\"x\"")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/uploads/" + id + "/part-urls")
                        .header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partNumbers\": [1]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/uploads").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.uploads").isEmpty());
    }

    @Test
    void anUploadASessionUsesCantBeDeletedUntilTheSessionEnds() throws Exception {
        var renter = bookings.renter();
        var content = "data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload = created(renter, "data.txt", content.length);
        var id = upload.get("id").asString();
        complete(renter, id, putParts(upload, content)).andExpect(status().isOk());
        var session = runningWith(renter, id);

        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-in-use"));

        end(session);
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.status").value("deleted"));
        assertThat(storage.size(objectKey(id))).isEmpty();
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void pendingUploadsExpireAfterADayAndReadyOnesAfter30DaysUnlessInUse() throws Exception {
        var renter = bookings.renter();
        var pending = created(renter, "abandoned.bin", 10).get("id").asString();
        var content = "kept".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var readyUpload = created(renter, "kept.txt", content.length);
        var ready = readyUpload.get("id").asString();
        complete(renter, ready, putParts(readyUpload, content)).andExpect(status().isOk());
        var used = created(renter, "used.txt", content.length);
        var inUse = used.get("id").asString();
        complete(renter, inUse, putParts(used, content)).andExpect(status().isOk());
        var session = runningWith(renter, inUse);
        var now = uploads.now();

        expiry.expire(now.plus(Duration.ofHours(23)));
        assertThat(statusOf(pending)).isEqualTo("pending");

        expiry.expire(now.plus(Duration.ofHours(25)));
        assertThat(statusOf(pending)).isEqualTo("expired");
        assertThat(statusOf(ready)).isEqualTo("ready");

        expiry.expire(now.plus(Duration.ofDays(31)));
        assertThat(statusOf(ready)).isEqualTo("expired");
        assertThat(storage.size(objectKey(ready))).isEmpty();
        assertThat(statusOf(inUse)).as("its session hasn't ended").isEqualTo("ready");
        assertThat(storage.size(objectKey(inUse))).isPresent();

        end(session);
        expiry.expire(now.plus(Duration.ofDays(31)));
        assertThat(statusOf(inUse)).isEqualTo("expired");
    }

    /** A session of the renter's, running on a host, that takes the upload as its input. */
    private TestBookings.Booked runningWith(TestUser renter, String uploadId) throws Exception {
        var host = bookings.host();
        var session = bookings.book(renter);
        jdbc.update(
                "insert into sessions.session_inputs (session_id, name, upload_id) values (?, 'file', ?::uuid)",
                session,
                uploadId);
        host.reply(host.awaitOffer(TestBookings.WAIT).getOfferId(), true, TestBookings.ACCEPT);
        host.ack(host.awaitStart(TestBookings.WAIT));
        report(host, session, gpu.agent.v1.Session.SessionState.SESSION_STATE_RUNNING);
        bookings.awaitState(renter, session, "RUNNING");
        return new TestBookings.Booked(host, renter, session);
    }

    /** The session's agent reports it failed, so it ends. */
    private void end(TestBookings.Booked booked) throws Exception {
        report(booked.host(), booked.sessionId(), gpu.agent.v1.Session.SessionState.SESSION_STATE_FAILED);
        bookings.awaitState(booked.renter(), booked.sessionId(), "FAILED");
    }

    private static void report(
            com.idleo.controlplane.TestHosts.ConnectedHost host, UUID session, gpu.agent.v1.Session.SessionState state)
            throws InterruptedException {
        var status = gpu.agent.v1.Session.SessionStatus.newBuilder()
                .setSessionId(session.toString())
                .setAttempt(1)
                .setState(state);
        if (state == gpu.agent.v1.Session.SessionState.SESSION_STATE_FAILED) {
            status.setError(gpu.agent.v1.Session.SessionError.newBuilder().setCode("job_failed"));
        }
        var request = com.idleo.controlplane.TestAgent.request()
                .setSessionStatus(status)
                .build();
        host.stream().send(request);
        host.stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        TestBookings.WAIT);
    }

    private String statusOf(String uploadId) {
        return jdbc.queryForObject("select status from sessions.uploads where id = ?::uuid", String.class, uploadId);
    }
}
