package com.idleo.controlplane.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit log's checks and off-site copy (ADR 0051). With no {@code offsite.endpoint} nothing is copied (tests and the
 * end-to-end stack); the prod profile refuses to start that way (ProductionSettingsGuard).
 *
 * @param offsite where each day's file goes: in S5 an R2 bucket with a lock on the prefix (docs/runbooks/audit-log.md)
 */
@ConfigurationProperties("identity.audit")
record AuditProperties(
        @DefaultValue Offsite offsite,
        @DefaultValue("Asia/Kolkata") String zone,
        @DefaultValue("1m") Duration refreshEvery) {

    record Offsite(
            @DefaultValue("") String endpoint,
            @DefaultValue("") String bucket,
            @DefaultValue("audit") String prefix,
            @DefaultValue("auto") String region,
            @DefaultValue("") String accessKeyId,
            @DefaultValue("") String secretAccessKey) {

        boolean configured() {
            return !endpoint.isBlank();
        }
    }
}
