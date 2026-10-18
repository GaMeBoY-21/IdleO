package com.idleo.controlplane.hosts.internal;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Windows app's signed release feed (ADR 0034): the {@code releases} bucket in the object storage the stack
 * already uses (MinIO locally, R2 in production), read every {@code refresh-every}. Only a manifest that verifies
 * against {@code public-key} (the raw Ed25519 key in base64, {@code IDLEO_RELEASE_PUBLIC_KEY}) is served; with no key
 * the feed is off and {@code GET /v1/agent-releases/latest} says there's no release. Download links last {@code
 * link-ttl}.
 *
 * @param endpoint the S3 endpoint the links are signed for, which laptops must reach (a LAN address with {@code
 *     ./start.sh --lan}); empty turns the feed off
 */
@ConfigurationProperties("hosts.releases")
record AgentReleaseProperties(
        @Nullable URI endpoint,
        @DefaultValue("releases") String bucket,
        @DefaultValue("auto") String region,
        @Nullable String accessKeyId,
        @Nullable String secretAccessKey,
        @Nullable String publicKey,
        @DefaultValue("5m") Duration refreshEvery,
        @DefaultValue("1h") Duration linkTtl) {

    boolean configured() {
        return endpoint != null
                && !endpoint.toString().isBlank()
                && publicKey != null
                && !publicKey.isBlank()
                && accessKeyId != null
                && !accessKeyId.isBlank()
                && secretAccessKey != null
                && !secretAccessKey.isBlank();
    }
}
