package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An admin's second factor and admin session (ADR 0027). Only an admin reaches these, with an ID token; anyone else
 * gets the 404 of a path that doesn't exist. Codes and secrets here are shown once and never stored in the clear.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
@SecurityRequirement(name = "idToken")
class AdminSessionController {

    private final AdminSecondFactor secondFactor;
    private final AdminSessions sessions;

    AdminSessionController(AdminSecondFactor secondFactor, AdminSessions sessions) {
        this.secondFactor = secondFactor;
        this.sessions = sessions;
    }

    /**
     * Where the admin stands: whether their authenticator is set up, and whether the admin session in the header is
     * live, with when it lapses unless used.
     */
    record AdminSessionStatus(
            boolean enrolled, boolean active, @Nullable Instant expiresAt) {}

    /** A new authenticator secret, shown once: as text to type in, and as a link for a QR code. */
    record AdminEnrolment(String secret, String otpauthUri) {}

    /** A 6-digit code from the authenticator app. */
    record AdminCodeRequest(@NotBlank @Size(max = 16) String totp) {}

    /** A code from the authenticator app, or one of the recovery codes; give one. */
    record AdminSessionRequest(
            @Size(max = 16) @Nullable String totp,
            @Size(max = 32) @Nullable String recoveryCode) {}

    /**
     * An admin session: send {@code token} in the {@code Admin-Session} header with the ID token. It lapses at
     * {@code expiresAt} unless used; each use renews it for 30 minutes.
     */
    record AdminSessionResponse(String token, Instant expiresAt) {}

    /** The admin session, and the ten recovery codes, each usable once instead of a code, shown only now. */
    record AdminEnrolmentConfirmed(String token, Instant expiresAt, List<String> recoveryCodes) {}

    @GetMapping("/session")
    AdminSessionStatus status(
            @AuthenticationPrincipal CurrentUser user,
            Authentication authentication,
            @Parameter(description = "The admin session's token, if there is one")
                    @RequestHeader(name = AdminSessions.HEADER, required = false)
                    @Nullable
                    String token) {
        var admin = secondFactor.admin(user.id()).orElseThrow(AdminSessionController::notFound);
        var expiry = token == null
                ? null
                : sessions.expiry(user.id(), identity(authentication), token).orElse(null);
        return new AdminSessionStatus(admin.enrolled(), expiry != null, expiry);
    }

    @PostMapping("/second-factor")
    @ResponseStatus(HttpStatus.CREATED)
    AdminEnrolment enrol(@AuthenticationPrincipal CurrentUser user) {
        var enrolment = secondFactor
                .enrol(user.id())
                .orElseThrow(() -> ApiProblem.of(
                        Problem.SECOND_FACTOR_EXISTS, "Your authenticator is already set up; sign in with a code."));
        return new AdminEnrolment(enrolment.secret(), enrolment.otpauthUri());
    }

    @PostMapping("/second-factor/confirm")
    AdminEnrolmentConfirmed confirm(
            @AuthenticationPrincipal CurrentUser user,
            Authentication authentication,
            @Valid @RequestBody AdminCodeRequest request) {
        var accepted = accepted(secondFactor.confirm(user.id(), identity(authentication), request.totp()));
        return new AdminEnrolmentConfirmed(
                accepted.session().token(), accepted.session().expiresAt(), accepted.recoveryCodes());
    }

    @PostMapping("/session")
    AdminSessionResponse start(
            @AuthenticationPrincipal CurrentUser user,
            Authentication authentication,
            @Valid @RequestBody AdminSessionRequest request) {
        if ((request.totp() == null) == (request.recoveryCode() == null)) {
            throw ApiProblem.invalid("totp", "Give a code from your authenticator, or a recovery code; one of them.");
        }
        var accepted = accepted(
                secondFactor.signIn(user.id(), identity(authentication), request.totp(), request.recoveryCode()));
        return new AdminSessionResponse(
                accepted.session().token(), accepted.session().expiresAt());
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = AdminSessions.HEADER, required = false) @Nullable String token) {
        if (token != null) {
            sessions.end(user.id(), token);
        }
    }

    private static AdminSecondFactor.Outcome.Accepted accepted(AdminSecondFactor.Outcome outcome) {
        return switch (outcome) {
            case AdminSecondFactor.Outcome.Accepted accepted -> accepted;
            case AdminSecondFactor.Outcome.Wrong wrong ->
                throw ApiProblem.of(
                        Problem.ADMIN_CODE_INVALID,
                        wrong.locked()
                                ? "That code isn't right. It was the fifth wrong code in 15 minutes, so admin access"
                                        + " is now locked until an operator grants it again."
                                : "That code isn't right, or was already used.");
            case AdminSecondFactor.Outcome.NotNow notNow -> throw ApiProblem.of(notNow.problem(), notNow.why());
        };
    }

    private static UUID identity(Authentication authentication) {
        return ((IdentityAuthenticationToken) authentication)
                .identityId()
                .orElseThrow(AdminSessionController::notFound);
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's nothing at this address.");
    }
}
