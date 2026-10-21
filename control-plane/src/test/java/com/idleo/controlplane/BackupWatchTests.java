package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The control plane's own look at the backups (ADR 0036), against a database that archives through a command that
 * always fails, so its WAL waits as it would with storage gone, and MinIO holding WAL-G's completion markers. Its own
 * database and storage, stopped mid-test, so it never shares a context.
 */
@SpringBootTest(
        properties = {
            "backups.storage.bucket=" + BackupWatchTests.BUCKET,
            "backups.storage.prefix=drill",
            "backups.storage.region=us-east-1",
            "backups.create-bucket=true",
            // The test checks when it means to.
            "backups.check-every=1h"
        })
@Import({
    BackupWatchTests.ArchivingDatabase.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestStorageConfiguration.class
})
@DirtiesContext
class BackupWatchTests {

    static final String BUCKET = "backups-test";

    @TestConfiguration(proxyBeanMethods = false)
    static class ArchivingDatabase {
        @Bean
        @ServiceConnection
        PostgreSQLContainer archivingPostgres() {
            return TestcontainersConfiguration.newPostgres()
                    .withCommand("postgres", "-c", "fsync=off", "-c", "archive_mode=on", "-c", "archive_command=false");
        }

        @Bean
        @ServiceConnection(name = "redis")
        org.testcontainers.containers.GenericContainer<?> valkey() {
            return TestcontainersConfiguration.newValkey();
        }

        @Bean
        DynamicPropertyRegistrar backupStorage(TestStorageConfiguration.StorageContainer storage) {
            return registry -> {
                registry.add("backups.storage.endpoint", storage::endpoint);
                registry.add("backups.storage.access-key-id", () -> TestStorageConfiguration.ROOT_USER);
                registry.add("backups.storage.secret-access-key", () -> TestStorageConfiguration.ROOT_PASSWORD);
            };
        }
    }

    @Autowired
    BackupWatch watch;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestStorageConfiguration.StorageContainer storage;

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    void itSeesTheNewestFullBackupAndWalWaitingToBeArchived() throws Exception {
        watch.check();
        assertThat(gauge("backups.readable")).isEqualTo(1);
        // Migrations may already have filled a segment, which waits since archiving fails.
        var lagBefore = gauge("backups.wal.lag");
        assertThat(gauge("backups.full.age"))
                .as("no backup yet: counted from when the watch started")
                .isBetween(0.0, 120.0);

        try (var s3 = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        TestStorageConfiguration.ROOT_USER, TestStorageConfiguration.ROOT_PASSWORD)))
                .forcePathStyle(true)
                .build()) {
            s3.putObject(
                    request -> request.bucket(BUCKET)
                            .key("drill/basebackups_005/base_000000010000000000000002" + BackupWatch.SENTINEL),
                    RequestBody.fromString("{}"));
            // Another prefix's backups are someone else's.
            s3.putObject(
                    request -> request.bucket(BUCKET)
                            .key("other/basebackups_005/base_000000010000000000000009" + BackupWatch.SENTINEL),
                    RequestBody.fromString("{}"));
        }
        jdbc.execute("create temporary table wal_maker as select generate_series(1, 10000) as n");
        jdbc.queryForObject("select pg_switch_wal()::text", String.class);
        Thread.sleep(Duration.ofSeconds(2));

        watch.check();
        assertThat(gauge("backups.full.age")).isBetween(0.0, 30.0);
        assertThat(gauge("backups.wal.lag"))
                .as("archiving fails, so the oldest segment waits longer")
                .isGreaterThanOrEqualTo(Math.max(1.0, lagBefore + 1.0));
        assertThat(gauge("backups.readable")).isEqualTo(1);

        storage.stop();
        watch.check();
        assertThat(gauge("backups.readable")).as("storage gone").isZero();
    }
}
