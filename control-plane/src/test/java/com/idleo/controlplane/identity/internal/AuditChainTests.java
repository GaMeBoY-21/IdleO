package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.OneAtATime;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestStorageConfiguration;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The audit log is tamper-evident (ADR 0051): chained by the database, checked, copied off site and searchable. */
@ControlPlaneTest
class AuditChainTests {

    @Autowired
    AuditTrail audit;

    @Autowired
    AuditChain chain;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient client;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    OneAtATime oneAtATime;

    @Autowired
    com.idleo.controlplane.IdGenerator ids;

    @Autowired
    TestStorageConfiguration.StorageContainer storage;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID record(String reason) {
        var target = UUID.randomUUID();
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                AuditTrail.Actor.operator("nikki"),
                "host.activate",
                "host",
                target.toString(),
                reason,
                Map.of("status", "pending"),
                Map.of("status", "active"))));
        return target;
    }

    private long seqOf(UUID target) {
        return jdbc.queryForObject(
                "select seq from identity.audit_log where target_id = ?", Long.class, target.toString());
    }

    /** Runs {@code change} with the table's own triggers off, as someone with the database's keys could. */
    private void bypassingTheTriggers(String... statements) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            for (var statement : statements) {
                jdbc.update(statement);
            }
        });
    }

    @Test
    void eachRowFollowsTheOneBeforeIt() {
        var first = record("the first of two");
        var second = record("the second of two");
        var rows = jdbc.queryForMap(
                "select b.seq as seq, a.seq as before_seq, b.prev_hash = a.hash as joined,"
                        + " length(b.hash) as bytes, b.hash = (select hash from identity.audit_chain_head) as is_head"
                        + " from identity.audit_log a, identity.audit_log b"
                        + " where a.target_id = ? and b.target_id = ?",
                first.toString(),
                second.toString());
        assertThat(rows.get("seq")).isEqualTo((Long) rows.get("before_seq") + 1);
        assertThat(rows)
                .containsEntry("joined", true)
                .containsEntry("bytes", 32)
                .containsEntry("is_head", true);
        assertThat(chain.verify("test").intact()).isTrue();
    }

    @Test
    void aWriterCannotChooseItsPlaceOrHash() {
        var target = UUID.randomUUID();
        jdbc.update(
                "insert into identity.audit_log (id, actor_kind, actor_name, action, target_type, target_id, reason,"
                        + " created_at, seq, prev_hash, hash) values (?, 'operator', 'x', 'host.activate', 'host', ?,"
                        + " 'forged place', now(), 1, '\\x00', '\\x00')",
                UUID.randomUUID(),
                target.toString());
        assertThat(seqOf(target)).isGreaterThan(1);
        assertThat(chain.verify("test").intact()).isTrue();
    }

    @Test
    void changingOneRowInTheDatabaseIsDetected() {
        var target = record("the reason as it was");
        var seq = seqOf(target);
        record("a later row");
        bypassingTheTriggers(
                "update identity.audit_log set reason = 'a better story' where target_id = '" + target + "'");
        try {
            var check = chain.verify("test");
            assertThat(check.intact()).isFalse();
            assertThat(check.brokenSeq()).isEqualTo(seq);
            assertThat(check.detail()).contains("hash");
            assertThat(jdbc.queryForObject(
                            "select intact from identity.audit_chain_checks where id = ?", Boolean.class, check.id()))
                    .isFalse();
            var registry = new SimpleMeterRegistry();
            var watching = new AuditChain(client, ids, clock, oneAtATime, registry);
            watching.refresh();
            assertThat(registry.get("audit.chain.broken").gauge().value()).isEqualTo(1);
        } finally {
            bypassingTheTriggers(
                    "update identity.audit_log set reason = 'the reason as it was' where target_id = '" + target + "'");
        }
        assertThat(chain.verify("test").intact()).isTrue();
    }

    @Test
    void removingARowIsDetected() {
        var target = record("about to go missing");
        var seq = seqOf(target);
        record("a later row");
        transactions.executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            jdbc.execute("create temporary table kept_row on commit preserve rows as"
                    + " select * from identity.audit_log where target_id = '" + target + "'");
            jdbc.update("delete from identity.audit_log where target_id = ?", target.toString());
        });
        try {
            var check = chain.verify("test");
            assertThat(check.intact()).isFalse();
            assertThat(check.brokenSeq()).isEqualTo(seq + 1);
        } finally {
            transactions.executeWithoutResult(status -> {
                jdbc.execute("set local session_replication_role = replica");
                jdbc.update("insert into identity.audit_log select * from kept_row");
                jdbc.execute("drop table kept_row");
            });
        }
        assertThat(chain.verify("test").intact()).isTrue();
    }

    @Test
    void theApplicationCanNeitherChangeTheLogNorItsHead() {
        var privileges = jdbc.queryForMap(
                "select has_table_privilege('idleo_application', 'identity.audit_log', 'UPDATE') as log_update,"
                        + " has_table_privilege('idleo_application', 'identity.audit_log', 'DELETE') as log_delete,"
                        + " has_table_privilege('idleo_application', 'identity.audit_log', 'TRUNCATE') as log_truncate,"
                        + " has_table_privilege('idleo_application', 'identity.audit_log', 'INSERT') as log_insert,"
                        + " has_table_privilege('idleo_application', 'identity.audit_chain_head', 'UPDATE') as head");
        assertThat(privileges)
                .containsEntry("log_update", false)
                .containsEntry("log_delete", false)
                .containsEntry("log_truncate", false)
                .containsEntry("log_insert", true)
                .containsEntry("head", false);
    }

    @Test
    void anAdminsActRecordsWhereItCameFromAndCanBeFoundByItsRequest() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var answer = mvc.perform(admin.as(post("/admin/v1/invites"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("User-Agent", "Test browser")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"note\":\"for a friend\"}"))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).isEqualTo(201);
        var requestId = answer.getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        JsonNode entry = json.readTree(
                        mvc.perform(admin.as(get("/admin/v1/audit").param("requestId", requestId)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("entries")
                .get(0);
        assertThat(entry.get("action").asString()).isEqualTo("invite.create");
        assertThat(entry.get("adminSessionId").asString()).isNotBlank();
        assertThat(entry.get("userAgent").asString()).isEqualTo("Test browser");
        assertThat(jdbc.queryForObject(
                        "select host(ip) from identity.audit_log where request_id = ?", String.class, requestId))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void anExportIsCsvWithTheAddressMaskedAndIsLogged() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var target = record("=SUM(a formula)");
        var answer = mvc.perform(admin.as(post("/admin/v1/audit/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":\"" + target + "\",\"reason\":\"quarterly review\"}"))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getContentType()).startsWith("text/csv");
        assertThat(answer.getContentAsString()).startsWith("seq,id,").contains("\"'=SUM(a formula)\"");
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'audit.export' and actor_user_id = ?",
                        String.class,
                        admin.id()))
                .isEqualTo("quarterly review");
    }

    @Test
    void verifyingFromTheConsoleAnswersAndIsKept() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var check = json.readTree(mvc.perform(admin.as(post("/admin/v1/audit/verify")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(check.get("intact").asBoolean()).isTrue();
        var standing = json.readTree(mvc.perform(admin.as(get("/admin/v1/audit/standing")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(standing.get("latestCheck").get("trigger").asString()).isEqualTo("admin");
    }

    @Test
    void eachFinishedDayIsWrittenOffSiteOnceAndAdmitsNoOtherBytes() throws IOException {
        var bucket = "audit-" + UUID.randomUUID();
        var s3 = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        TestStorageConfiguration.ROOT_USER, TestStorageConfiguration.ROOT_PASSWORD)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3.createBucket(request -> request.bucket(bucket));
        var properties = new AuditProperties(
                new AuditProperties.Offsite(
                        storage.endpoint(),
                        bucket,
                        "audit",
                        "auto",
                        TestStorageConfiguration.ROOT_USER,
                        TestStorageConfiguration.ROOT_PASSWORD),
                "Asia/Kolkata",
                Duration.ofMinutes(1));
        var registry = new SimpleMeterRegistry();
        var copies = new AuditOffsiteCopies(client, clock, oneAtATime, json, properties, registry);

        var target = record("copied off site");
        clock.advance(Duration.ofDays(3));
        copies.copyFinishedDays();

        var newest = copies.newest().orElseThrow();
        var keys = s3.listObjectsV2(request -> request.bucket(bucket)).contents().stream()
                .map(software.amazon.awssdk.services.s3.model.S3Object::key)
                .toList();
        assertThat(keys).contains(newest.objectKey(), newest.objectKey().replace(".ndjson.gz", ".head.json"));
        var withRow = jdbc.queryForObject(
                "select object_key from identity.audit_offsite_copies where ? between first_seq and last_seq",
                String.class,
                seqOf(target));
        assertThat(new String(
                        new GZIPInputStream(s3.getObject(
                                        request -> request.bucket(bucket).key(withRow)))
                                .readAllBytes(),
                        StandardCharsets.UTF_8))
                .contains(target.toString(), "copied off site");
        var head = json.readTree(s3.getObjectAsBytes(
                        request -> request.bucket(bucket).key(newest.objectKey().replace(".ndjson.gz", ".head.json")))
                .asUtf8String());
        assertThat(head.get("lastSeq").asLong()).isEqualTo(newest.lastSeq());
        assertThat(head.get("headHash").asString()).isEqualTo(newest.headHash());
        assertThat(HexFormat.of().parseHex(newest.headHash())).hasSize(32);

        // The same day again finds the same bytes, and nothing changes.
        int copied = jdbc.queryForObject("select count(*) from identity.audit_offsite_copies", Integer.class);
        copies.copyFinishedDays();
        assertThat(jdbc.queryForObject("select count(*) from identity.audit_offsite_copies", Integer.class))
                .isEqualTo(copied);

        // A day missed is the alert's to say: its gauge reads the time since the newest day's end.
        copies.refresh();
        assertThat(registry.get("audit.offsite.age").gauge().value())
                .isLessThan(Duration.ofHours(25).toSeconds());
        clock.advance(Duration.ofDays(2));
        assertThat(registry.get("audit.offsite.age").gauge().value())
                .isGreaterThan(Duration.ofHours(26).toSeconds());
        jdbc.update("delete from identity.audit_offsite_copies");
    }
}
