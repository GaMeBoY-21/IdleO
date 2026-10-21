package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.FreshSignIn;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Deleting the account (ADR 0037). See the privacy module's documentation and {@link Deletions}. */
@RestController
@ConditionalOnWebApplication
class DeletionController {

    private final Deletions deletions;
    private final FreshSignIn fresh;
    private final LegalSettings legal;

    DeletionController(Deletions deletions, FreshSignIn fresh, LegalSettings legal) {
        this.deletions = deletions;
        this.fresh = fresh;
        this.legal = legal;
    }

    /** How long what erasure keeps is kept, in days, from {@code legal.retention}, for the page to say so. */
    record KeptFor(long moneyRecordsDays, long securityLogDays, long backupsDays) {}

    /**
     * Where the person stands: their latest request, if any; what stops deletion now; what they'd give up; how long
     * the wait is; and whether this sign-in is recent enough to ask ({@code freshSignIn}).
     */
    record DeletionStanding(
            Deletions.@Nullable DeletionRequest request,
            List<PersonalData.Blocker> blockers,
            List<PersonalData.Loss> losses,
            long waitingDays,
            KeptFor keptFor,
            boolean freshSignIn) {}

    private DeletionStanding standing(UUID userId, Authentication authentication) {
        var standing = deletions.standing(userId);
        return new DeletionStanding(
                standing.request(),
                standing.blockers(),
                standing.losses(),
                standing.waitingPeriod().toDays(),
                new KeptFor(
                        legal.retention().moneyRecords().toDays(),
                        legal.retention().securityEvents().toDays(),
                        legal.retention().backups().toDays()),
                fresh.within(authentication, Deletions.FRESH));
    }

    @OpenToWaitingUsers
    @GetMapping("/v1/me/deletion")
    DeletionStanding get(@AuthenticationPrincipal CurrentUser user, Authentication authentication) {
        return standing(user.id(), authentication);
    }

    /**
     * Asks to delete the account: a confirmation link goes to the account's email. Only from a browser signed in in
     * the last 10 minutes (403 {@code fresh-sign-in-required}); 409 {@code deletion-blocked} with {@code blockers} while
     * something has to end first; 409 {@code deletion-already-requested} once it's confirmed. Asked again before
     * then, a new link is sent and the old one stops working.
     */
    @OpenToWaitingUsers
    @BrowserSignInOnly
    @PostMapping("/v1/me/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeletionStanding request(@AuthenticationPrincipal CurrentUser user, Authentication authentication) {
        if (!fresh.within(authentication, Deletions.FRESH)) {
            throw ApiProblem.of(
                    Problem.FRESH_SIGN_IN_REQUIRED,
                    "Sign in again: deleting an account needs a sign-in in the last 10 minutes.");
        }
        deletions.request(user.id());
        return standing(user.id(), authentication);
    }

    /** The emailed link: its request and token. */
    record ConfirmDeletion(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 100) String token) {}

    /**
     * Confirms with the emailed link: the wait begins, and an email says when the account will be deleted and how to
     * cancel. 400 {@code deletion-link-invalid} for a wrong, used or expired link.
     */
    @OpenToWaitingUsers
    @BrowserSignInOnly
    @PostMapping("/v1/me/deletion/confirm")
    DeletionStanding confirm(
            @AuthenticationPrincipal CurrentUser user,
            Authentication authentication,
            @Valid @RequestBody ConfirmDeletion request) {
        deletions.confirm(user.id(), request.requestId(), request.token());
        return standing(user.id(), authentication);
    }

    /** Cancels the waiting deletion; 409 {@code no-deletion-pending} when nothing is waiting, or erasing has begun. */
    @OpenToWaitingUsers
    @BrowserSignInOnly
    @DeleteMapping("/v1/me/deletion")
    DeletionStanding cancel(@AuthenticationPrincipal CurrentUser user, Authentication authentication) {
        deletions.cancel(user.id());
        return standing(user.id(), authentication);
    }

    /** The emailed cancel link's token. */
    record CancelDeletion(@NotBlank @Size(max = 100) String token) {}

    /** What a cancel link did. */
    record DeletionCancelled(String state) {}

    /**
     * Cancels a scheduled deletion with a link from its emails, with no sign-in: the link's token is the proof. 400
     * {@code deletion-link-invalid} for a link that isn't one; 409 {@code no-deletion-pending} once erasing has begun.
     */
    @PostMapping("/v1/deletion-requests/{id}/cancel")
    DeletionCancelled cancelByLink(@PathVariable UUID id, @Valid @RequestBody CancelDeletion request) {
        return new DeletionCancelled(deletions.cancelByLink(id, request.token()).state());
    }
}
