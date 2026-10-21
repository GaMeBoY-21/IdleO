package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.UserDirectory;
import com.idleo.controlplane.notifications.AccountEmails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

    private final UserDirectory users;
    private final AccountEmails emails;
    private final AuditTrail audit;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;

    AdminNoticesController(
            UserDirectory users,
            AccountEmails emails,
            AuditTrail audit,
            IdGenerator ids,
            TransactionTemplate transactions) {
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

    @PostMapping("/admin/v1/notices")
    AdminNoticeSent send(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminNotice request) {
        var id = ids.newId();
        var actor = audit.admin(admin);
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
