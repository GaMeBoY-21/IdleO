package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.UserDirectory;
import com.idleo.controlplane.notifications.AccountEmails;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A written notice to chosen people, emailed (ADR 0037, docs/runbooks/breach.md): what a breach requires telling the
 * people it touched, in words a person decided on. Recorded in the audit log by the people's ids and how many it
 * reached, never by their addresses; the words themselves are the person's to keep from their email.
 */
@RestController
@ConditionalOnWebApplication
class AdminNoticesController {

    /** More people than this at once is a bulk action, which another admin approves (ADR 0051). */
    static final int BULK = 20;

    static final String NOTICE = "notice.send";

    private final UserDirectory users;
    private final AccountEmails emails;
    private final AuditTrail audit;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;
    private final Approvals approvals;

    AdminNoticesController(
            UserDirectory users,
            AccountEmails emails,
            AuditTrail audit,
            IdGenerator ids,
            TransactionTemplate transactions,
            Approvals approvals) {
        this.approvals = approvals;
        this.users = users;
        this.emails = emails;
        this.audit = audit;
        this.ids = ids;
        this.transactions = transactions;
    }

    /** Whom to tell, by account id, the email's subject and words, and why (5 to 500 characters, as every reason). */
    record AdminNotice(
            @NotEmpty @Size(max = 500) List<UUID> userIds,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** How many were emailed, and who has no address we can write to. */
    record AdminNoticeSent(UUID noticeId, int sent, List<UUID> withoutAddress) {}

    @RequiresPermission(AdminPermission.NOTICES_PUBLISH)
    @PostMapping("/admin/v1/notices")
    @ApiResponse(
            responseCode = "200",
            description = "Sent",
            content = @Content(schema = @Schema(implementation = AdminNoticeSent.class)))
    @ApiResponse(
            responseCode = "202",
            description = "To more than 20 people, so it goes to another admin",
            content = @Content(schema = @Schema(implementation = Approvals.AdminApproval.class)))
    ResponseEntity<?> send(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminNotice request) {
        var people = request.userIds().stream().distinct().toList();
        if (people.size() > BULK) {
            return approvals.submit(
                    admin,
                    NOTICE,
                    null,
                    null,
                    request,
                    List.of(new Approvals.ApprovalChange("people emailed: " + request.subject(), 0, people.size())),
                    request.reason());
        }
        return ResponseEntity.ok(sendNow(request, audit.admin(admin)));
    }

    /** Emails them: at once, or once another admin has approved. */
    AdminNoticeSent sendNow(AdminNotice request, AuditTrail.Actor actor) {
        var id = ids.newId();
        var people = request.userIds().stream().distinct().toList();
        var withoutAddress = people.stream()
                .filter(user -> users.verifiedEmail(user).isEmpty())
                .toList();
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                actor,
                "notice.send",
                "notice",
                id.toString(),
                request.reason(),
                null,
                Map.of(
                        "userIds", people.stream().map(UUID::toString).toList(),
                        "withoutAddress", withoutAddress.size()))));
        int sent = 0;
        for (var user : people) {
            var address = users.verifiedEmail(user);
            if (address.isPresent()) {
                emails.sendOnce(
                        address.get(),
                        UUID.nameUUIDFromBytes(("notice:" + id + ":" + user).getBytes(StandardCharsets.UTF_8)),
                        request.subject().strip(),
                        request.body().strip(),
                        "/account/help/");
                sent++;
            }
        }
        return new AdminNoticeSent(id, sent, withoutAddress);
    }
}
