package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's deletion, for the admin console (ADR 0037): where it stands, for a privacy request; and the one way past
 * the wait, a legal order, with its reason in the audit log. An admin can't start or speed up an ordinary deletion.
 */
@RestController
@RequestMapping("/admin/v1/users/{id}/deletion")
@ConditionalOnWebApplication
class AdminDeletionsController {

    private final Deletions deletions;
    private final AuditTrail audit;

    AdminDeletionsController(Deletions deletions, AuditTrail audit) {
        this.deletions = deletions;
        this.audit = audit;
    }

    /** The person's latest request, if any, and what stops deletion now. */
    record AdminDeletion(Deletions.@Nullable DeletionRequest request, List<String> blockers) {}

    @GetMapping
    AdminDeletion get(@PathVariable UUID id) {
        var standing = deletions.standing(id);
        return new AdminDeletion(
                standing.request(),
                standing.blockers().stream().map(blocker -> blocker.code()).toList());
    }

    /** Erases the account at the next run of the erasure job, under a legal order the reason names. Audited. */
    @PostMapping("/legal-order")
    AdminDeletion legalOrder(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        deletions.underLegalOrder(id, request.reason(), audit.admin(admin));
        return get(id);
    }
}
