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
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Uploads (ADR 0019, ADR 0022) against real MinIO: a folder of files or one, each file's parts PUT by the client
 * straight to storage over pre-signed links asked for per file, then completed with the ETags; the control plane
 * never carries the bytes.
 */
@ControlPlaneTest
class UploadApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MIB = 1024 * 1024;

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

    private static final long GIB = 1024L * MIB;

    private ResultActions create(TestUser user, String key, String name, Map<String, Long> files) throws Exception {
        return TestUploads.create(mvc, user, key, name, files);
    }

    private JsonNode created(TestUser user, String name, Map<String, Long> files) throws Exception {
        return TestUploads.created(mvc, user, name, files);
    }

    private JsonNode created(TestUser user, String filename, long size) throws Exception {
        return created(user, filename, Map.of(filename, size));
    }

    private List<Map<String, Object>> putFile(TestUser user, JsonNode upload, String path, byte[] content)
            throws Exception {
        return TestUploads.putFile(mvc, user, upload, path, content);
    }

    private ResultActions complete(TestUser user, String id, String path, List<Map<String, Object>> parts)
            throws Exception {
        return TestUploads.complete(mvc, user, id, Map.of(path, parts));
    }

    private static String id(JsonNode upload) {
        return upload.at("/upload/id").asString();
    }

    private String objectKey(String id, String path) {
        return jdbc.queryForObject(
                "select object_key from sessions.upload_files where upload_id = ?::uuid and path = ?",
                String.class,
                id,
                path);
    }

    /** A ready upload of one file; its id. */
    private String ready(TestUser user, String filename, byte[] content) throws Exception {
        return TestUploads.ready(mvc, user, filename, content).toString();
    }

    @Test
    void aFolderUploadsFileByFileAndIsReadyOnceEveryFileIs() throws Exception {
        var user = TestUsers.signUp(mvc);
        var weights = randomBytes(11 * MIB, 1);
        var config = "{\"hidden_size\": 64}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var files = new java.util.LinkedHashMap<String, Long>();
        files.put("config.json", (long) config.length);
        files.put("weights/model 00001.safetensors", (long) weights.length);
        files.put("src/__init__.py", 0L);

        var upload = created(user, "My Model", files);

        var id = id(upload);
        assertThat(upload.at("/upload/status").asString()).isEqualTo("pending");
        assertThat(upload.at("/upload/fileCount").asInt()).isEqualTo(3);
        assertThat(upload.at("/upload/sizeBytes").asLong()).isEqualTo(config.length + weights.length);
        assertThat(upload.get("files").findValuesAsString("path"))
                .containsExactly("config.json", "src/__init__.py", "weights/model 00001.safetensors");
        assertThat(upload.get("files").findValues("partCount"))
                .extracting(JsonNode::asInt)
                .containsExactly(1, 0, 3);
        assertThat(upload.has("parts")).as("links come per file, on demand").isFalse();
        assertThat(objectKey(id, "weights/model 00001.safetensors"))
                .isEqualTo("uploads/" + id + "/files/weights/model_00001.safetensors");

        complete(
                        user,
                        id,
                        "weights/model 00001.safetensors",
                        putFile(user, upload, "weights/model 00001.safetensors", weights))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.status").value("pending"))
                .andExpect(jsonPath("$.files[2].done").value(true))
                .andExpect(jsonPath("$.files[0].done").value(false));
        TestUploads.complete(
                        mvc,
                        user,
                        id,
                        Map.of(
                                "config.json",
                                putFile(user, upload, "config.json", config),
                                "src/__init__.py",
                                List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.status").value("ready"))
                .andExpect(jsonPath("$.upload.name").value("My Model"))
                .andExpect(jsonPath("$.upload.completedAt").isNotEmpty());

        assertThat(storage.read(objectKey(id, "weights/model 00001.safetensors"))
                        .orElseThrow())
                .isEqualTo(weights);
        assertThat(storage.read(objectKey(id, "config.json")).orElseThrow()).isEqualTo(config);
        assertThat(storage.size(objectKey(id, "src/__init__.py"))).contains(0L);
        complete(user, id, "config.json", List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.status").value("ready"));
        mvc.perform(get("/v1/uploads").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.uploads[0].id").value(id))
                .andExpect(jsonPath("$.uploads[0].name").value("My Model"))
                .andExpect(jsonPath("$.uploads[0].fileCount").value(3))
                .andExpect(jsonPath("$.uploads[0].lastUsedAt").doesNotExist());
    }

    @Test
    void aOnePartFileIsStillMultipart() throws Exception {
        var user = TestUsers.signUp(mvc);
        var content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload = created(user, "hello.txt", content.length);

        var parts = putFile(user, upload, "hello.txt", content);

        assertThat(parts).hasSize(1);
        complete(user, id(upload), "hello.txt", parts)
                .andExpect(jsonPath("$.upload.status").value("ready"));
    }

    @Test
    void aFileOfTheWrongSizeFailsTheWholeUploadAndDeletesEveryFile() throws Exception {
        var user = TestUsers.signUp(mvc);
        var upload = created(user, "two", Map.of("good.bin", 10L, "short.bin", 6L * MIB));
        var id = id(upload);
        complete(user, id, "good.bin", putFile(user, upload, "good.bin", randomBytes(10, 2)))
                .andExpect(status().isOk());
        // Every part uploads, but the last carries half what was declared.
        var parts = putFile(user, upload, "short.bin", randomBytes(5 * MIB + MIB / 2, 2));

        complete(user, id, "short.bin", parts)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));

        mvc.perform(get("/v1/uploads/" + id).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.upload.status").value("failed"));
        assertThat(storage.size(objectKey(id, "short.bin"))).isEmpty();
        assertThat(storage.size(objectKey(id, "good.bin")))
                .as("the whole upload goes")
                .isEmpty();
        complete(user, id, "short.bin", parts).andExpect(jsonPath("$.type").value("/problems/upload-not-ready"));
    }

    @Test
    void aMissingPartOrAWrongEtagIsRefusedAndTheUploadStaysPending() throws Exception {
        var user = TestUsers.signUp(mvc);
        var content = randomBytes(6 * MIB, 3);
        var upload = created(user, "two.bin", content.length);
        var id = id(upload);
        var parts = putFile(user, upload, "two.bin", content);

        complete(user, id, "two.bin", parts.subList(0, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));
        complete(user, id, "two.bin", List.of(parts.get(0), Map.of("number", 2, "etag", "\"0000\"")))
                .andExpect(jsonPath("$.type").value("/problems/upload-parts-invalid"));
        mvc.perform(get("/v1/uploads/" + id).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.upload.status").value("pending"));

        complete(user, id, "two.bin", parts)
                .andExpect(jsonPath("$.upload.status").value("ready"));
    }

    @Test
    void partLinksComePerFileOnDemandAFewAtATime() throws Exception {
        var user = TestUsers.signUp(mvc);
        var upload = created(user, "slow", Map.of("a.bin", 11L * MIB, "big.bin", 600L * MIB, "empty", 0L));
        var id = id(upload);

        TestUploads.links(mvc, user, id, "a.bin", List.of(2, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts[0].number").value(2))
                .andExpect(jsonPath("$.parts[1].number").value(3))
                .andExpect(jsonPath("$.urlsExpireAt").isNotEmpty());
        TestUploads.links(mvc, user, id, "a.bin", List.of(4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("partNumbers"));
        TestUploads.links(
                        mvc,
                        user,
                        id,
                        "big.bin",
                        java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList())
                .andExpect(status().isBadRequest());
        TestUploads.links(
                        mvc,
                        user,
                        id,
                        "big.bin",
                        java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts.length()").value(100));
        TestUploads.links(mvc, user, id, "nowhere.bin", List.of(1))
                .andExpect(jsonPath("$.errors[0].field").value("path"));
        TestUploads.links(mvc, user, id, "empty", List.of(1))
                .andExpect(jsonPath("$.errors[0].field").value("path"));
    }

    @Test
    void theSameKeyRepeatsTheUploadAndADifferentBodyIsRefused() throws Exception {
        var user = TestUsers.signUp(mvc);
        var first = JSON.readTree(create(user, "upload-1", "a", Map.of("a.bin", 100L))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        create(user, "upload-1", "a", Map.of("a.bin", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.id").value(id(first)));
        create(user, "upload-1", "a", Map.of("a.bin", 101L))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
        create(user, "\"has space\"", "a", Map.of("a.bin", 100L))
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-invalid"));
    }

    @Test
    void badNamesPathsAndSizesAreRefused() throws Exception {
        var user = TestUsers.signUp(mvc);
        for (var name : List.of("..", ".", "a/b", "a\\b", "nul\u0000", "x".repeat(256))) {
            create(user, UUID.randomUUID().toString(), name, Map.of("a.bin", 10L))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }
        for (var path : List.of("../a.bin", "a/../b", "/etc/passwd", "a//b", "a/", "./a", "a\\b", "a/\u0007")) {
            create(user, UUID.randomUUID().toString(), "f", Map.of(path, 10L))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("files[0].path"));
        }
        var clash = new java.util.LinkedHashMap<String, Long>();
        clash.put("a b.txt", 1L);
        clash.put("a_b.txt", 1L);
        create(user, UUID.randomUUID().toString(), "f", clash)
                .andExpect(jsonPath("$.errors[0].field").value("files[1].path"));
        var fileAndFolder = new java.util.LinkedHashMap<String, Long>();
        fileAndFolder.put("model", 1L);
        fileAndFolder.put("model/config.json", 1L);
        create(user, UUID.randomUUID().toString(), "f", fileAndFolder)
                .andExpect(jsonPath("$.errors[0].field").value("files"));
        create(user, UUID.randomUUID().toString(), "f", Map.of("a.bin", -1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
    }

    @Test
    void anUploadHoldsUpTo40GiBIn1000FilesAndARenterUpTo60GiB() throws Exception {
        var user = TestUsers.signUp(mvc);
        create(user, UUID.randomUUID().toString(), "too big", Map.of("a.bin", 20 * GIB, "b.bin", 20 * GIB + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("files"));
        var tooMany = new java.util.LinkedHashMap<String, Long>();
        java.util.stream.IntStream.rangeClosed(1, 1_001).forEach(i -> tooMany.put("f" + i, 1L));
        create(user, UUID.randomUUID().toString(), "too many", tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("files"));

        // Pending uploads count at their declared size, with nothing sent yet.
        created(user, "a 40 GiB model", Map.of("a.bin", 20 * GIB, "b.bin", 20 * GIB - 1_000));
        tooMany.remove("f1001");
        assertThat(created(user, "a thousand files", tooMany)
                        .at("/upload/fileCount")
                        .asInt())
                .isEqualTo(1_000);
        created(user, "the rest", Map.of("c.bin", 20 * GIB));
        create(user, UUID.randomUUID().toString(), "one byte more", Map.of("d.bin", 1L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-quota-exceeded"));
    }

    @Test
    void anotherRenterSeesNoneOfIt() throws Exception {
        var owner = TestUsers.signUp(mvc);
        var other = TestUsers.signUp(mvc);
        var id = id(created(owner, "mine.bin", 10));

        mvc.perform(get("/v1/uploads/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        complete(other, id, "mine.bin", List.of(Map.of("number", 1, "etag", "\"x\"")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        TestUploads.links(mvc, other, id, "mine.bin", List.of(1)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/uploads").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.uploads").isEmpty());
    }

    @Test
    void anUploadASessionUsesCantBeDeletedUntilTheSessionEnds() throws Exception {
        var renter = bookings.renter();
        var id = ready(renter, "data.txt", "data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var session = runningWith(renter, id);

        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/upload-in-use"));

        end(session);
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.upload.status").value("deleted"));
        assertThat(storage.size(objectKey(id, "data.txt"))).isEmpty();
        mvc.perform(get("/v1/uploads").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.uploads[?(@.id == '" + id + "')]").isEmpty());
        mvc.perform(delete("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void pendingUploadsExpireAfterADayAndReadyOnesAfter30DaysUnlessInUse() throws Exception {
        var renter = bookings.renter();
        var pendingUpload = created(renter, "abandoned", Map.of("a.bin", 11L * MIB, "b.bin", 10L));
        var pending = id(pendingUpload);
        // One file has begun: its multipart upload is open, and expiry aborts it.
        putFile(renter, pendingUpload, "a.bin", randomBytes(11 * MIB, 4));
        var content = "kept".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ready = ready(renter, "kept.txt", content);
        var inUse = ready(renter, "used.txt", content);
        var session = runningWith(renter, inUse);
        var now = uploads.now();

        expiry.expire(now.plus(Duration.ofHours(23)));
        assertThat(statusOf(pending)).isEqualTo("pending");

        expiry.expire(now.plus(Duration.ofHours(25)));
        assertThat(statusOf(pending)).isEqualTo("expired");
        assertThat(statusOf(ready)).isEqualTo("ready");

        expiry.expire(now.plus(Duration.ofDays(31)));
        assertThat(statusOf(ready)).isEqualTo("expired");
        assertThat(storage.size(objectKey(ready, "kept.txt"))).isEmpty();
        assertThat(statusOf(inUse)).as("its session hasn't ended").isEqualTo("ready");
        assertThat(storage.size(objectKey(inUse, "used.txt"))).isPresent();

        end(session);
        expiry.expire(now.plus(Duration.ofDays(31)));
        assertThat(statusOf(inUse)).isEqualTo("expired");
    }

    @Test
    void aReadyUploadExpires30DaysAfterASessionLastUsedItNotAfterItWasMade() throws Exception {
        var renter = bookings.renter();
        var id = ready(renter, "greetings.txt", "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var now = uploads.now();
        // As if it was made 20 days ago: it would expire in 10.
        jdbc.update(
                "update sessions.uploads set completed_at = ?, expires_at = ? where id = ?::uuid",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(20))),
                java.sql.Timestamp.from(now.plus(Duration.ofDays(10))),
                id);

        mvc.perform(post("/v1/sessions")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "templateId",
                                "echo-input",
                                "maxPricePaisePerMin",
                                5,
                                "maxSpendPaise",
                                10_000,
                                "params",
                                Map.of("greeting", "hi"),
                                "inputs",
                                Map.of("file", Map.of("uploadId", id))))))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/uploads/" + id).header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.upload.lastUsedAt").isNotEmpty());
        var session = jdbc.queryForObject(
                "select session_id from sessions.session_inputs where upload_id = ?::uuid", UUID.class, id);
        // It ends, whether it found a host or not, so it no longer holds the upload.
        mvc.perform(post("/v1/sessions/" + session + "/cancel").header("Authorization", renter.bearer()));
        assertThat(jdbc.queryForObject("select state from sessions.sessions where id = ?", String.class, session))
                .isIn("CANCELLED", "NO_HOST_FREE");
        expiry.expire(now.plus(Duration.ofDays(11)));
        assertThat(statusOf(id)).as("used today: kept 30 days from now").isEqualTo("ready");
        expiry.expire(now.plus(Duration.ofDays(31)));
        assertThat(statusOf(id)).isEqualTo("expired");
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
