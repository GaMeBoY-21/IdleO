package com.idleo.controlplane.sessions.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage for sessions (ADR 0016). One bucket per environment.
 *
 * @param provider {@code minio} for local runs, tests and the end-to-end test; {@code r2} in production,
 *     which refuses to start with {@code minio}
 * @param endpoint the S3 endpoint the control plane and agents use
 * @param region the S3 signing region ({@code auto} for R2)
 * @param accessKeyId the control plane's own key: STS parent for MinIO, and checks, listings and
 *     download links on either
 * @param grantTtl how long a storage grant lasts
 * @param downloadLinkTtl how long a renter's output download link lasts
 * @param r2 Cloudflare R2's temporary-credentials API, for the {@code r2} provider
 */
@ConfigurationProperties("sessions.storage")
@Validated
record StorageProperties(
        @NotNull Provider provider,
        @NotNull URI endpoint,
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        @NotNull Duration grantTtl,
        @NotNull Duration downloadLinkTtl,
        @Nullable R2 r2) {

    enum Provider {
        MINIO,
        R2
    }

    /**
     * @param apiBaseUrl Cloudflare's API, {@code https://api.cloudflare.com/client/v4}
     * @param accountId the Cloudflare account that owns the bucket
     * @param apiToken an API token allowed to create temporary R2 credentials
     * @param parentAccessKeyId the R2 access key the temporary credentials derive from
     */
    record R2(URI apiBaseUrl, String accountId, String apiToken, String parentAccessKeyId) {}
}
