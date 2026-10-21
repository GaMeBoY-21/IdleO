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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's data and deletion, for the admin console (ADR 0037), as a privacy request needs: start an export of
 * everything for them; see where a deletion stands; and the one way past the wait, a legal order, with its reason in
 * the audit log. An admin can't start or speed up an ordinary deletion.
 */
@RestController
@RequestMapping("/admin/v1/users/{id}")
@ConditionalOnWebApplication
class AdminDeletionsController {

    private final Deletions deletions;
    private final Exports exports;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    AdminDeletionsController(Deletions deletions, Exports exports, AuditTrail audit, TransactionTemplate transactions) {
        this.deletions = deletions;
        this.exports = exports;
        this.audit = audit;
        this.transactions = transactions;
    }

    /** The person's latest request, if any, and what stops deletion now. */
    record AdminDeletion(Deletions.@Nullable DeletionRequest request, List<String> blockers) {}

    @GetMapping("/deletion")
    AdminDeletion get(@PathVariable UUID id) {
        var standing = deletions.standing(id);
        return new AdminDeletion(
                standing.request(),
                standing.blockers().stream().map(blocker -> blocker.code()).toList());
    }

    /** Erases the account at the next run of the erasure job, under a legal order the reason names. Audited. */
    @PostMapping("/deletion/legal-order")
    AdminDeletion legalOrder(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        deletions.underLegalOrder(id, request.reason(), audit.admin(admin));
        return get(id);
    }

    /**
     * Starts an export of everything for the person, as their privacy request asks (ADR 0037): emailed to them when
     * it's ready, like one they asked for themselves, and under the same limits. Audited.
     */
    @PostMapping("/exports")
    Exports.Export export(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var actor = audit.admin(admin);
        var export = exports.request(id);
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                actor,
                "export.start",
                "user",
                id.toString(),
                request.reason(),
                null,
                java.util.Map.of("exportId", export.id().toString()))));
        return export;
    }
}
