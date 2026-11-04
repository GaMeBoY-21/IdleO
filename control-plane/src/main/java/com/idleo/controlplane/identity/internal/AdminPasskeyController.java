package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An admin's own passkeys (ADR 0051): make one with user verification, each named; list them; remove one. Changing them
 * needs a recent factor. Every value in the WebAuthn exchange is base64url text.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
class AdminPasskeyController {

    private final AdminPasskeys passkeys;

    AdminPasskeyController(AdminPasskeys passkeys) {
        this.passkeys = passkeys;
    }

    record AdminPasskey(
            UUID id,
            String name,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {

        static AdminPasskey of(AdminPasskeys.Passkey passkey) {
            return new AdminPasskey(passkey.id(), passkey.name(), passkey.createdAt(), passkey.lastUsedAt());
        }
    }

    record AdminPasskeyList(List<AdminPasskey> passkeys) {}

    /** A credential the browser may use, for {@code allowCredentials} and {@code excludeCredentials}. */
    record CredentialDescriptor(String type, String id, List<String> transports) {

        static CredentialDescriptor of(PublicKeyCredentialDescriptor descriptor) {
            return new CredentialDescriptor(
                    "public-key",
                    descriptor.getId().toBase64UrlString(),
                    descriptor.getTransports() == null
                            ? List.of()
                            : descriptor.getTransports().stream()
                                    .map(AuthenticatorTransport::getValue)
                                    .toList());
        }
    }

    /** What {@code navigator.credentials.create} needs, with a challenge to answer within five minutes. */
    record PasskeyRegistrationOptions(
            UUID challengeId,
            String challenge,
            String rpId,
            String rpName,
            String userId,
            String userName,
            String userDisplayName,
            List<Integer> algorithms,
            List<CredentialDescriptor> exclude,
            long timeoutMs) {}

    /** What {@code navigator.credentials.get} needs, with a challenge to answer within five minutes. */
    record PasskeyAssertionOptions(
            UUID challengeId, String challenge, String rpId, List<CredentialDescriptor> allow, long timeoutMs) {}

    /** A new passkey's name, its challenge, and what the authenticator answered. */
    record PasskeyRegistration(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 2048) String credentialId,
            @NotBlank @Size(max = 8192) String clientDataJson,
            @NotBlank @Size(max = 65536) String attestationObject,
            @Size(max = 8) @Nullable List<String> transports) {}

    /** What an authenticator answered to a challenge. */
    record PasskeyAssertion(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 2048) String credentialId,
            @NotBlank @Size(max = 8192) String clientDataJson,
            @NotBlank @Size(max = 8192) String authenticatorData,
            @NotBlank @Size(max = 8192) String signature,
            @Size(max = 2048) @Nullable String userHandle) {

        AdminPasskeys.Assertion assertion() {
            return new AdminPasskeys.Assertion(
                    challengeId, credentialId, clientDataJson, authenticatorData, signature, userHandle);
        }
    }

    @RequiresPermission(value = AdminPermission.SELF, stepUp = true)
    @PostMapping("/passkeys/options")
    PasskeyRegistrationOptions registrationOptions(@AuthenticationPrincipal CurrentUser admin) {
        var registering = passkeys.startRegistration(admin.id());
        var options = registering.options();
        return new PasskeyRegistrationOptions(
                registering.challengeId(),
                options.getChallenge().toBase64UrlString(),
                options.getRp().getId(),
                options.getRp().getName(),
                options.getUser().getId().toBase64UrlString(),
                options.getUser().getName(),
                options.getUser().getDisplayName(),
                options.getPubKeyCredParams().stream()
                        .map(parameter -> (int) parameter.getAlg().getValue())
                        .toList(),
                options.getExcludeCredentials().stream()
                        .map(CredentialDescriptor::of)
                        .toList(),
                options.getTimeout().toMillis());
    }

    /** Keeps a new passkey. 400 {@code passkey-not-accepted} when it doesn't check out. */
    @RequiresPermission(value = AdminPermission.SELF, stepUp = true)
    @PostMapping("/passkeys")
    @ResponseStatus(HttpStatus.CREATED)
    AdminPasskey register(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody PasskeyRegistration request) {
        return passkeys.finishRegistration(
                        admin.id(),
                        request.challengeId(),
                        request.name().strip(),
                        request.credentialId(),
                        request.clientDataJson(),
                        request.attestationObject(),
                        request.transports() == null ? List.of() : request.transports())
                .map(AdminPasskey::of)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.PASSKEY_NOT_ACCEPTED, "That passkey wasn't accepted. Try making it again."));
    }

    @RequiresPermission(AdminPermission.SELF)
    @GetMapping("/passkeys")
    AdminPasskeyList list(@AuthenticationPrincipal CurrentUser admin) {
        return new AdminPasskeyList(
                passkeys.list(admin.id()).stream().map(AdminPasskey::of).toList());
    }

    /** Removes one of the admin's own passkeys. Their authenticator and recovery codes stay. */
    @RequiresPermission(value = AdminPermission.SELF, stepUp = true)
    @DeleteMapping("/passkeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        if (!passkeys.remove(admin.id(), id)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "You have no such passkey.");
        }
    }

    /**
     * A challenge to sign with one of the admin's passkeys, to start a session or step up. 404 {@code not-found} when they
     * have none: they use a code.
     */
    @RequiresPermission(AdminPermission.SELF)
    @PostMapping("/session/passkey-options")
    PasskeyAssertionOptions assertionOptions(@AuthenticationPrincipal CurrentUser admin) {
        var asserting = passkeys.startAssertion(admin.id())
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "You have no passkey yet."));
        var options = asserting.options();
        return new PasskeyAssertionOptions(
                asserting.challengeId(),
                options.getChallenge().toBase64UrlString(),
                options.getRpId(),
                options.getAllowCredentials().stream()
                        .map(CredentialDescriptor::of)
                        .toList(),
                options.getTimeout().toMillis());
    }
}
