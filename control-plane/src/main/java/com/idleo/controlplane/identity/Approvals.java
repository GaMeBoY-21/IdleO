package com.idleo.controlplane.identity;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.Paise;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The one approval engine (ADR 0051). A controller hands a gated action here instead of doing it, and answers with what
 * this gives back: 202 with the request. Each module registers the actions it can do as {@link GatedAction}s, so the
 * engine can do them later, as they were asked, however long the wait and whoever approves.
 *
 * <p>Stopping things is never gated: suspending, holding a payout, blocking an image. Starting or releasing them is.
 */
public interface Approvals {

    /** How long a request with no one else to approve it waits before it runs: money's, or access's. */
    enum Cooling {
        MONEY,
        ACCESS
    }

    /** One line of what the action will change, for the approver to read. */
    record ApprovalChange(
            String what, @Nullable Object from, @Nullable Object to) {}

    /** The request as it's being run: its parameters, and the requester's reason and name. */
    record Run(UUID approvalId, JsonNode params, String reason, AuditTrail.Actor actor) {}

    /** A file an approved export gives its requester, made when they fetch it, so it's never kept. */
    record File(byte[] bytes, String contentType, String name) {}

    /** One kind of gated action, in the module that does it. */
    interface GatedAction {

        /** Its name in the request, such as {@code user.unsuspend}. */
        String kind();

        AdminPermission permission();

        Cooling cooling();

        /**
         * Does it, as it was asked, with the requester as the actor. {@link RuntimeException} means it was refused and
         * nothing changed; its message goes to the approver.
         */
        void run(Run run);

        /** For an action whose result is a file: made once, for its requester, after it's approved. */
        default Optional<File> download(Run run) {
            return Optional.empty();
        }

        /** Whether it ends in a {@link #download}. */
        default boolean downloads() {
            return false;
        }
    }

    /** A request, as the console shows it. */
    record AdminApproval(
            UUID id,
            String kind,
            String permission,
            @Nullable String targetType,
            @Nullable String targetId,
            List<ApprovalChange> changes,
            UUID requestedBy,
            String reason,
            String state,
            boolean solo,
            @Nullable Instant runAfter,
            @Nullable UUID decidedBy,
            @Nullable String decisionReason,
            @Nullable Instant decidedAt,
            @Nullable String failure,
            boolean downloadable,
            Instant expiresAt,
            Instant createdAt,
            long version) {}

    /** An action that's only a kind, a permission, how it waits, and what it does. */
    static GatedAction action(
            String kind, AdminPermission permission, Cooling cooling, java.util.function.Consumer<Run> run) {
        return new GatedAction() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public AdminPermission permission() {
                return permission;
            }

            @Override
            public Cooling cooling() {
                return cooling;
            }

            @Override
            public void run(Run asked) {
                run.accept(asked);
            }
        };
    }

    /** An action whose whole result is a file its requester fetches once it's approved: an export. */
    static GatedAction fileAction(
            String kind, AdminPermission permission, Cooling cooling, java.util.function.Function<Run, File> file) {
        return new GatedAction() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public AdminPermission permission() {
                return permission;
            }

            @Override
            public Cooling cooling() {
                return cooling;
            }

            @Override
            public void run(Run asked) {
                // Approving it only opens the way: the file is made when it's fetched, and never kept.
            }

            @Override
            public boolean downloads() {
                return true;
            }

            @Override
            public Optional<File> download(Run asked) {
                return Optional.of(file.apply(asked));
            }
        };
    }

    /**
     * Makes {@code kind}'s request, with the target's version as it is now.
     *
     * @param targetType the name in {@link AdminRecords}, or null for an action on no record
     * @param params what {@link GatedAction#run} needs, as plain JSON
     * @param changes what it will change, shown to the approver
     * @return 202 with the request, for the controller to answer with
     */
    ResponseEntity<AdminApproval> submit(
            CurrentUser admin,
            String kind,
            @Nullable String targetType,
            @Nullable String targetId,
            Object params,
            List<ApprovalChange> changes,
            String reason);

    /** Whether a refund of this much needs a second admin: above {@code identity.approvals.refund-limit}. */
    boolean refundNeedsApproval(Paise amount);
}
