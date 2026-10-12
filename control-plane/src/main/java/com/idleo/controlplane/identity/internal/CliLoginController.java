package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Device sign-in for the CLI (ADR 0025, shaped like RFC 8628). Starting one and polling it need no sign-in; finding
 * it by its code and approving it need a browser's sign-in (KeyRules). A login that's unknown, or polled with the
 * wrong token, is a 404.
 */
@RestController
@RequestMapping("/v1/cli-logins")
class CliLoginController {

    private final CliLogins logins;
    private final IdentityProperties properties;

    CliLoginController(CliLogins logins, IdentityProperties properties) {
        this.logins = logins;
        this.properties = properties;
    }

    record StartCliLoginRequest(@NotBlank @Size(max = 60) String deviceName) {

        StartCliLoginRequest {
            deviceName = deviceName == null ? null : deviceName.strip();
        }
    }

    /** What the CLI shows and keeps: the code to compare, the link to open, and the token to poll with. */
    record StartedCliLogin(
            UUID id,

            @Schema(description = "The code to compare in the browser, like WDJB-MJHT")
            String userCode,

            @Schema(description = "The web app's page that approves this login, with the code in it")
            String verificationUrl,

            @Schema(description = "The CLI's secret for polling; never shown to the person")
            String pollToken,

            long intervalSeconds,
            long expiresInSeconds) {}

    /** A login waiting for approval, as the approval page shows it. */
    record PendingCliLogin(UUID id, String deviceName, Instant expiresAt) {

        static PendingCliLogin of(CliLogins.Pending pending) {
            return new PendingCliLogin(pending.id(), pending.deviceName(), pending.expiresAt());
        }
    }

    record PollCliLoginRequest(@NotBlank @Size(max = 128) String pollToken) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StartedCliLogin start(@Valid @RequestBody StartCliLoginRequest request) {
        var started = logins.start(request.deviceName());
        var link = UriComponentsBuilder.fromUri(properties.webAppUrl())
                .path("/cli-login/")
                .queryParam("code", started.userCode())
                .build()
                .toUriString();
        return new StartedCliLogin(
                started.id(),
                started.userCode(),
                link,
                started.pollToken(),
                properties.cliLogin().pollInterval().toSeconds(),
                properties.cliLogin().ttl().toSeconds());
    }

    /** The approval page's lookup: the login a code names, while it can be approved. */
    @GetMapping
    PendingCliLogin find(@RequestParam String code) {
        return logins.findPending(code)
                .map(PendingCliLogin::of)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.NOT_FOUND, "There's no CLI sign-in waiting with that code. Start a new one."));
    }

    @PostMapping("/{id}/approve")
    PendingCliLogin approve(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return switch (logins.approve(id, user.id())) {
            case CliLogins.Approval.Approved approved -> PendingCliLogin.of(approved.login());
            case CliLogins.Approval.Expired _ -> throw expired();
            case CliLogins.Approval.NotFound _ -> throw notFound();
        };
    }

    /**
     * The CLI's poll. Until the login is approved, 400 {@code authorization-pending}; once it is, the new full key,
     * this once; after that, or once the login expires, 400 {@code cli-login-expired}.
     */
    @PostMapping("/{id}/token")
    @ApiResponse(
            responseCode = "200",
            description = "The CLI's new key, with its secret this once",
            content = @Content(schema = @Schema(implementation = ApiKeyController.CreatedApiKey.class)))
    ApiKeyController.CreatedApiKey poll(@PathVariable UUID id, @Valid @RequestBody PollCliLoginRequest request) {
        return switch (logins.redeem(id, request.pollToken())) {
            case CliLogins.Redemption.Issued issued -> ApiKeyController.CreatedApiKey.of(issued.key());
            case CliLogins.Redemption.WaitingForApproval _ ->
                throw ApiProblem.of(
                        Problem.AUTHORIZATION_PENDING,
                        "Not approved yet. Open the link in a browser, sign in and approve the code.");
            case CliLogins.Redemption.Expired _ -> throw expired();
            case CliLogins.Redemption.NotFound _ -> throw notFound();
            case CliLogins.Redemption.TooManyKeys _ -> throw ApiKeyController.tooMany();
        };
    }

    private static ApiProblem expired() {
        return ApiProblem.of(Problem.CLI_LOGIN_EXPIRED, "This CLI sign-in has expired or was already used.");
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no such CLI sign-in.");
    }
}
