package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's API keys (ADR 0025). Creating one needs a browser's sign-in (KeyRules); listing and revoking
 * work with a full key too. Another person's key is a 404.
 */
@RestController
@RequestMapping("/v1/api-keys")
class ApiKeyController {

    static final String IDEMPOTENCY_TABLE = "identity.idempotency_keys";

    private final ApiKeyStore keys;
    private final IdempotentRequests idempotency;

    ApiKeyController(ApiKeyStore keys, IdempotentRequests idempotency) {
        this.keys = keys;
        this.idempotency = idempotency;
    }

    /** A key as a list shows it: its name, its first characters and its scope, never its secret. */
    record ApiKeyResponse(
            UUID id,
            String name,

            @Schema(description = "The key's first characters, like idleo_k3j9x2mq, to recognise it by")
            String prefix,

            @Schema(allowableValues = {"read", "full"}) String scope,
            @Schema(allowableValues = {"web", "cli-login"}) String createdVia,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {

        static ApiKeyResponse of(ApiKeyStore.Key key) {
            return new ApiKeyResponse(
                    key.id(),
                    key.name(),
                    key.displayPrefix(),
                    key.scope(),
                    key.createdVia(),
                    key.createdAt(),
                    key.lastUsedAt());
        }
    }

    record ApiKeyList(List<ApiKeyResponse> keys) {}

    /** A new key and, this once only, its secret. */
    record CreatedApiKey(
            UUID id,
            String name,
            String prefix,
            @Schema(allowableValues = {"read", "full"}) String scope,
            Instant createdAt,

            @Schema(description = "The key itself. It is shown only in this answer, never again")
            String key) {

        static CreatedApiKey of(ApiKeyStore.Created created) {
            var key = created.key();
            return new CreatedApiKey(
                    key.id(), key.name(), key.displayPrefix(), key.scope(), key.createdAt(), created.secret());
        }
    }

    record CreateApiKeyRequest(
            @NotBlank @Size(max = 60) String name,

            @NotNull @Schema(allowableValues = {"read", "full"})
            String scope) {

        CreateApiKeyRequest {
            name = name == null ? null : name.strip();
        }
    }

    @GetMapping
    ApiKeyList list(@AuthenticationPrincipal CurrentUser user) {
        return new ApiKeyList(
                keys.list(user.id()).stream().map(ApiKeyResponse::of).toList());
    }

    @BrowserSignInOnly
    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The new key, with its secret this once; a repeat with the same Idempotency-Key is a 409 "
                    + "secret-shown-once naming it",
            headers = @Header(name = "Location", description = "The key's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = CreatedApiKey.class)))
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateApiKeyRequest request) {
        var scope = ApiKeyStore.Scope.fromValue(request.scope())
                .orElseThrow(() -> ApiProblem.invalid("scope", "must be read or full"));
        return idempotency.executeShownOnce(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            var created = keys.create(user.id(), request.name(), scope, ApiKeyStore.Via.WEB)
                    .orElseThrow(ApiKeyController::tooMany);
            return ResponseEntity.created(
                            URI.create("/v1/api-keys/" + created.key().id()))
                    .body(CreatedApiKey.of(created));
        });
    }

    @ReadKeyMayRevokeItself("id")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        if (!keys.revoke(user.id(), id)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no such API key.");
        }
    }

    static ApiProblem tooMany() {
        return ApiProblem.of(
                Problem.TOO_MANY_API_KEYS,
                "You have " + ApiKeyStore.MAX_ACTIVE + " keys. Revoke one you no longer use, then create this one.");
    }
}
