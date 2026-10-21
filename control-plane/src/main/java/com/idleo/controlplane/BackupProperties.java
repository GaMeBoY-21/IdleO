package com.idleo.controlplane;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the database's backups are, for {@link BackupWatch} (ADR 0036, docs/runbooks/backups.md): the bucket and
 * prefix WAL-G writes to ({@code WALG_S3_PREFIX} is {@code s3://<bucket>/<prefix>}), read with a key that may only
 * list it. With no endpoint the watch is off (tests and the end-to-end stack); the prod profile refuses to start
 * without one (ProductionSettingsGuard).
 *
 * @param createBucket make the bucket if it's missing: development's MinIO only
 */
@ConfigurationProperties("backups")
public record BackupProperties(
        @DefaultValue Storage storage, @DefaultValue("1m") Duration checkEvery, boolean createBucket) {

    public record Storage(
            @DefaultValue("") String endpoint,
            @DefaultValue("") String bucket,
            @DefaultValue("") String prefix,
            @DefaultValue("auto") String region,
            @DefaultValue("") String accessKeyId,
            @DefaultValue("") String secretAccessKey) {}
}
