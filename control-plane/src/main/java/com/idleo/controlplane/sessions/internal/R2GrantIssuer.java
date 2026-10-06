package com.idleo.controlplane.sessions.internal;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Production (ADR 0016): Cloudflare R2's temporary access credentials, scoped to the bucket, prefixes and
 * permission, for the grant TTL. {@code POST /accounts/{account_id}/r2/temp-access-credentials}, as
 * documented at
 * https://developers.cloudflare.com/api/resources/r2/subresources/temporary_credentials/methods/create/. The
 * account, API token and parent key come from the environment; secrets never appear in an error.
 */
class R2GrantIssuer implements GrantIssuer {

    private final RestClient api;
    private final StorageProperties properties;
    private final StorageProperties.R2 r2;

    R2GrantIssuer(RestClient.Builder builder, StorageProperties properties) {
        if (properties.r2() == null) {
            throw new IllegalStateException("sessions.storage.r2 must be set for the r2 provider");
        }
        this.r2 = properties.r2();
        this.properties = properties;
        this.api = builder.baseUrl(r2.apiBaseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + r2.apiToken())
                .build();
    }

    @Override
    public StorageGrant issue(List<String> prefixes, boolean readWrite) {
        JsonNode response = api.post()
                .uri("/accounts/{accountId}/r2/temp-access-credentials", r2.accountId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "bucket", properties.bucket(),
                        "parentAccessKeyId", r2.parentAccessKeyId(),
                        "permission", readWrite ? "object-read-write" : "object-read-only",
                        "ttlSeconds", properties.grantTtl().toSeconds(),
                        "prefixes", prefixes))
                .retrieve()
                .onStatus(status -> status.isError(), (request, failure) -> {
                    throw new IllegalStateException("R2 refused temporary credentials: HTTP "
                            + failure.getStatusCode().value());
                })
                .body(JsonNode.class);
        if (response == null || !response.path("success").asBoolean(false)) {
            var errors = response == null ? "no body" : response.path("errors").toString();
            throw new IllegalStateException("R2 refused temporary credentials: " + errors);
        }
        var result = response.path("result");
        return new StorageGrant(
                properties.endpoint().toString(),
                properties.bucket(),
                result.path("accessKeyId").asString(),
                result.path("secretAccessKey").asString(),
                result.path("sessionToken").asString(),
                prefixes,
                readWrite,
                properties.grantTtl());
    }
}
