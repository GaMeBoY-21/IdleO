package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user's own account. See the identity module's package documentation. */
@RestController
@RequestMapping("/v1/me")
class MeController {

    private final UserRepository users;
    private final Clock clock;

    MeController(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    record MeResponse(
            UUID id, @Nullable String email, @Nullable String displayName, String status, Instant createdAt) {

        static MeResponse of(User user) {
            return new MeResponse(
                    user.id(),
                    user.email(),
                    user.displayName(),
                    user.userStatus().dbValue(),
                    user.createdAt());
        }
    }

    record UpdateMeRequest(@NotBlank @Size(max = 100) String displayName) {}

    @GetMapping
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        var user = load(currentUser);
        return ResponseEntity.ok().eTag(etag(user)).body(MeResponse.of(user));
    }

    @PatchMapping
    ResponseEntity<MeResponse> update(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody UpdateMeRequest request) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Send If-Match with the ETag from GET /v1/me.");
        }
        var user = load(currentUser);
        if (!matches(ifMatch, etag(user))) {
            throw stale();
        }
        try {
            var saved = users.save(user.withDisplayName(request.displayName().strip(), Instant.now(clock)));
            return ResponseEntity.ok().eTag(etag(saved)).body(MeResponse.of(saved));
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw stale();
        }
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

    private static ResponseStatusException stale() {
        return new ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED, "The account changed since you read it. GET /v1/me and try again.");
    }
}
