package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account. See the identity module's package documentation. */
@RestController
@RequestMapping("/v1/me")
class MeController {

    static final String INVALID = "That code isn't one we can accept.";
    static final String TOO_MANY = "Too many codes were tried. Wait an hour, then try again.";

    private final UserRepository users;
    private final InviteService invites;
    private final Clock clock;

    MeController(UserRepository users, InviteService invites, Clock clock) {
        this.users = users;
        this.invites = invites;
        this.clock = clock;
    }

    /**
     * The account. {@code status} is {@code waiting} (no invite code yet), {@code active} or {@code
     * suspended}; {@code signInMethods} says how this token signed in: {@code google} or {@code email-link}.
     */
    record MeResponse(
            UUID id,
            @Nullable String email,
            @Nullable String displayName,
            String status,
            List<String> signInMethods,
            Instant createdAt) {

        static MeResponse of(User user, Authentication authentication) {
            return new MeResponse(
                    user.id(),
                    user.email(),
                    user.displayName(),
                    user.userStatus().dbValue(),
                    authentication instanceof IdentityAuthenticationToken token ? token.signInMethods() : List.of(),
                    user.createdAt());
        }
    }

    /** The display name is 1 to 60 characters once the spaces around it are removed. */
    record ActivateRequest(@NotBlank @Size(max = 64) String code) {}

    record UpdateMeRequest(@NotBlank @Size(max = 60) String displayName) {

        UpdateMeRequest {
            displayName = displayName == null ? null : displayName.strip();
        }
    }

    @GetMapping
    @ApiResponse(
            responseCode = "200",
            description = "The signed-in user, created on their first request",
            headers =
                    @Header(
                            name = "ETag",
                            description = "Send it back as If-Match to change the user",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    ResponseEntity<MeResponse> get(@AuthenticationPrincipal CurrentUser currentUser, Authentication authentication) {
        var user = load(currentUser);
        return ResponseEntity.ok().eTag(etag(user)).body(MeResponse.of(user, authentication));
    }

    @PatchMapping
    @ApiResponse(
            responseCode = "200",
            description = "The changed user",
            headers =
                    @Header(
                            name = "ETag",
                            description = "The new version, for the next If-Match",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    ResponseEntity<MeResponse> update(
            @AuthenticationPrincipal CurrentUser currentUser,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody UpdateMeRequest request) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            throw ApiProblem.of(Problem.VERSION_REQUIRED, "Send If-Match with the ETag from GET /v1/me.");
        }
        var user = load(currentUser);
        if (!matches(ifMatch, etag(user))) {
            throw stale();
        }
        try {
            var saved = users.save(user.withDisplayName(request.displayName(), Instant.now(clock)));
            return ResponseEntity.ok().eTag(etag(saved)).body(MeResponse.of(saved, authentication));
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw stale();
        }
    }

    /**
     * Activates a waiting account with an invite code (ADR 0024). 200 with the active account. A wrong, used or
     * expired code is 400 {@code invite-invalid}, and the answer never says which. An account that's already
     * active is answered 200 without using the code.
     */
    @PostMapping("/activate")
    @ApiResponse(
            responseCode = "200",
            description = "The activated account",
            headers =
                    @Header(
                            name = "ETag",
                            description = "The new version, for the next If-Match",
                            schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    ResponseEntity<MeResponse> activate(
            @AuthenticationPrincipal CurrentUser currentUser,
            Authentication authentication,
            @Valid @RequestBody ActivateRequest request) {
        switch (invites.activate(currentUser.id(), request.code())) {
            case INVALID -> throw ApiProblem.of(Problem.INVITE_INVALID, INVALID);
            case TOO_MANY_ATTEMPTS -> throw ApiProblem.of(Problem.TOO_MANY_ATTEMPTS, TOO_MANY);
            case ACTIVATED, ALREADY_ACTIVE -> {}
        }
        var user = load(currentUser);
        return ResponseEntity.ok().eTag(etag(user)).body(MeResponse.of(user, authentication));
    }

    private User load(CurrentUser currentUser) {
        return users.findById(currentUser.id())
                .orElseThrow(() -> new IllegalStateException("signed-in user has no row"));
    }

    private static String etag(User user) {
        return "\"" + user.version() + "\"";
    }

    private static boolean matches(String ifMatch, String etag) {
        return Arrays.stream(ifMatch.split(",")).map(String::trim).anyMatch(etag::equals);
    }

    private static ApiProblem stale() {
        return ApiProblem.of(Problem.VERSION_STALE, "The account changed since you read it. GET /v1/me and try again.");
    }
}
