package com.idleo.controlplane;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Whether the database is being backed up (ADR 0036): the control plane looks for itself, once a minute, rather than
 * trusting the backup job to say so, since a backup that never reached storage didn't happen.
 *
 * <ul>
 *   <li>{@code backups.full.age}: seconds since the newest full backup finished, by the time storage stored its
 *       completion marker (WAL-G's {@code *_backup_stop_sentinel.json}); from the watch's own start while there is
 *       none.
 *   <li>{@code backups.wal.lag}: seconds the oldest WAL segment has waited to be archived ({@code .ready} files in
 *       {@code pg_wal/archive_status}, by the database's own clock); zero when none waits.
 *   <li>{@code backups.readable}: 1 while both could be read and the database archives at all ({@code archive_mode}
 *       on), else 0; the database user needs {@code pg_monitor} for the WAL check (docs/runbooks/deployment.md).
 * </ul>
 *
 * The no-backup alert reads all three (ops/alerts/backups.yaml). With no backup storage configured (tests, the
 * end-to-end stack) it watches nothing and has no meters; production refuses to start that way.
 */
@Component
@EnableConfigurationProperties(BackupProperties.class)
class BackupWatch implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BackupWatch.class);
    static final String SENTINEL = "_backup_stop_sentinel.json";

    private final BackupProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final @Nullable S3Client s3;
    private final Instant watchingSince;

    private volatile @Nullable Instant newestFull;
    private volatile double walLagSeconds;
    private volatile boolean readable;

    BackupWatch(BackupProperties properties, JdbcClient jdbc, Clock clock, MeterRegistry registry) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
        this.watchingSince = Instant.now(clock);
        var storage = properties.storage();
        if (storage.endpoint().isBlank()) {
            this.s3 = null;
            return;
        }
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey())))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(20))
                        .build())
                .build();
        if (properties.createBucket()) {
            createBucketForDevelopment(s3);
        }
        Gauge.builder("backups.full.age", this, watch -> watch.fullAgeSeconds())
                .description("Seconds since the newest full backup of the database finished")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("backups.wal.lag", this, watch -> watch.walLagSeconds)
                .description("Seconds the oldest WAL segment has waited to be archived")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("backups.readable", this, watch -> watch.readable ? 1 : 0)
                .description("1 while the backups can be read and the database archives its WAL")
                .register(registry);
    }

    double fullAgeSeconds() {
        var since = Optional.ofNullable(newestFull).orElse(watchingSince);
        return Duration.between(since, Instant.now(clock)).toMillis() / 1000.0;
    }

    /** Once a minute, from 5 seconds after start; never for an operator command, which runs no schedule. */
    @Scheduled(fixedDelayString = "${backups.check-every:1m}", initialDelayString = "PT5S")
    void check() {
        var storage = s3;
        if (storage == null) {
            return;
        }
        boolean ok = true;
        try {
            newestFull = newestFullBackup(storage).orElse(null);
        } catch (RuntimeException e) {
            ok = false;
            // The kind of failure only: a message could carry the endpoint or a key's id.
            log.warn("The backups can't be listed: {}", e.getClass().getSimpleName());
        }
        try {
            var archiving = jdbc.sql("select current_setting('archive_mode')")
                    .query(String.class)
                    .single();
            if ("off".equals(archiving)) {
                ok = false;
                log.warn("The database doesn't archive its WAL (archive_mode is off): it can't be restored to a point");
            }
            walLagSeconds = jdbc.sql("""
                            select coalesce(extract(epoch from now() - min(modification)), 0)
                            from pg_ls_archive_statusdir() where name like '%.ready'
                            """).query(Double.class).single();
        } catch (RuntimeException e) {
            ok = false;
            log.warn(
                    "The database's WAL archiving can't be read: {}",
                    e.getClass().getSimpleName());
        }
        readable = ok;
    }

    private Optional<Instant> newestFullBackup(S3Client s3) {
        var storage = properties.storage();
        var prefix = storage.prefix().isBlank() ? "" : storage.prefix().replaceAll("/+$", "") + "/";
        Instant newest = null;
        for (var page : s3.listObjectsV2Paginator(
                request -> request.bucket(storage.bucket()).prefix(prefix + "basebackups_005/"))) {
            for (var object : page.contents()) {
                if (object.key().endsWith(SENTINEL)
                        && (newest == null || object.lastModified().isAfter(newest))) {
                    newest = object.lastModified();
                }
            }
        }
        return Optional.ofNullable(newest);
    }

    private void createBucketForDevelopment(S3Client s3) {
        var bucket = properties.storage().bucket();
        try {
            s3.headBucket(request -> request.bucket(bucket));
        } catch (S3Exception missing) {
            try {
                s3.createBucket(request -> request.bucket(bucket));
                log.info("Created the development backup bucket {}", bucket);
            } catch (RuntimeException e) {
                log.warn(
                        "Couldn't create the development backup bucket {}: {}",
                        bucket,
                        e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.warn("The backup storage isn't reachable yet: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        if (s3 != null) {
            s3.close();
        }
    }
}
