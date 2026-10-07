package com.idleo.controlplane.sessions.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Uploads (ADR 0019, technical design "Limits").
 *
 * @param partSize each part's size but the last, which may be smaller; storage takes 5 MiB at the least
 * @param linkTtl how long a part's pre-signed link works
 * @param pendingTtl how long an upload may stay unfinished before it's aborted
 * @param readyTtl how long a finished upload is kept, unless a session that hasn't ended uses it
 * @param maxFileSize the largest file
 * @param quota the most a renter may have in pending and ready uploads together
 * @param expirySweepEvery how often expired uploads are looked for
 */
@ConfigurationProperties("sessions.uploads")
@Validated
record UploadProperties(
        @NotNull DataSize partSize,
        @NotNull Duration linkTtl,
        @NotNull Duration pendingTtl,
        @NotNull Duration readyTtl,
        @NotNull DataSize maxFileSize,
        @NotNull DataSize quota,
        @NotNull Duration expirySweepEvery) {}
