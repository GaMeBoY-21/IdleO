package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tickets, for the admin console (ADR 0037): the list by due date, overdue first; one ticket with its messages, the
 * person, and the session or machine it names, for the admin pages that show them; an answer; a change of status.
 */
@RestController
@RequestMapping("/admin/v1/tickets")
@ConditionalOnWebApplication
class AdminTicketsController {

    private final Tickets tickets;
    private final AuditTrail audit;
    private final Clock clock;

    AdminTicketsController(Tickets tickets, AuditTrail audit, Clock clock) {
        this.tickets = tickets;
        this.audit = audit;
        this.clock = clock;
    }

    /** A ticket as an admin sees it: who to answer, and whether it's past its due date. */
    record AdminTicket(
            UUID id,
            long number,
            @Nullable UUID userId,
            String topic,
            String status,
            @Nullable String replyTo,
            @Nullable UUID sessionId,
            @Nullable UUID hostId,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant dueAt,
            @Nullable Instant resolvedAt,
            boolean overdue) {

        static AdminTicket of(Tickets.StoredTicket ticket, Instant now) {
            return new AdminTicket(
                    ticket.id(),
                    ticket.number(),
                    ticket.userId(),
                    ticket.topic(),
                    ticket.status(),
                    ticket.replyTo(),
                    ticket.sessionId(),
                    ticket.hostId(),
                    ticket.createdAt(),
                    ticket.updatedAt(),
                    ticket.dueAt(),
                    ticket.resolvedAt(),
                    ticket.overdue(now));
        }
    }

    record AdminTicketPage(
            List<AdminTicket> tickets, @Nullable String nextCursor) {}

    record AdminTicketMessage(String author, String body, Instant createdAt) {}

    record AdminTicketThread(AdminTicket ticket, List<AdminTicketMessage> messages) {}

    /** The answer, and the status it leaves the ticket in: {@code waiting} for the person, or {@code resolved}. */
    record AdminAnswer(
            @NotBlank @Size(max = 5000) String message,

            @NotBlank @Schema(allowableValues = {"open", "waiting", "resolved"})
            String status) {}

    /**
     * The tickets: open ones first, by due date (overdue first) and then the oldest; {@code topic}, {@code status} and
     * {@code olderThanDays} narrow it; {@code cursor} is the next page's.
     */
    @GetMapping
    AdminTicketPage list(
            @RequestParam(required = false) @Nullable String topic,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer olderThanDays,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor.replaceAll("[^0-9]", "0"));
        var now = Instant.now(clock);
        var page = tickets.list(blank(topic), blank(status), olderThanDays, size + 1, offset);
        return new AdminTicketPage(
                page.stream()
                        .limit(size)
                        .map(ticket -> AdminTicket.of(ticket, now))
                        .toList(),
                page.size() > size ? String.valueOf(offset + size) : null);
    }

    @GetMapping("/{id}")
    AdminTicketThread get(@PathVariable UUID id) {
        var ticket = tickets.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such ticket."));
        return thread(ticket);
    }

    /** Answers the ticket: emailed to its address with its number in the subject. Audited. */
    @PostMapping("/{id}/answer")
    AdminTicketThread answer(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminAnswer request) {
        return thread(tickets.answer(id, request.message(), request.status(), audit.admin(admin)));
    }

    /** Changes the ticket's status, with a reason. Audited. */
    @PostMapping("/{id}/status")
    AdminTicketThread status(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReasonedStatus request) {
        return thread(tickets.setStatus(id, request.status(), request.reason(), audit.admin(admin)));
    }

    /** A status and the reason for it (5 to 500 characters, no contact details, as every admin reason). */
    record AdminReasonedStatus(
            @NotBlank @Schema(allowableValues = {"open", "waiting", "resolved"})
            String status,

            @NotBlank @Size(min = 5, max = 500) @com.idleo.controlplane.NoContactDetails
            String reason) {}

    private AdminTicketThread thread(Tickets.StoredTicket ticket) {
        return new AdminTicketThread(
                AdminTicket.of(ticket, Instant.now(clock)),
                tickets.messages(ticket.id()).stream()
                        .map(message -> new AdminTicketMessage(message.author(), message.body(), message.createdAt()))
                        .toList());
    }

    private static @Nullable String blank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
