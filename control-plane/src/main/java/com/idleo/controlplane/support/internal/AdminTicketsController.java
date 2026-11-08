package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    /** The list by due date, overdue first, then the oldest; resolved ones after; and its CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("tickets", AdminPermission.TICKETS_READ, true)
            .bulk("user", "user_id", "user_id", "'Ticket ' || right(cast(id as text), 8)", "tag", "warn")
            .from("support.tickets")
            .sort(
                    "due",
                    AdminListing.Type.TIME,
                    "case when status = 'resolved' then timestamptz 'infinity' else coalesce(due_at, timestamptz 'infinity') end")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("number", AdminListing.Type.NUMBER, "number")
            .sortedBy("due", false)
            .filter("topic", "topic = cast(:topic as text)")
            .filter("status", "status = cast(:status as text)")
            .filter("before", "created_at < cast(:before as timestamptz)")
            .filter("user", "user_id = cast(:user as uuid)")
            .filter("tagged", "user_id in (:tagged)")
            .csv("id", "id")
            .csv("number", "number")
            .csv("topic", "topic")
            .csv("status", "status")
            .csv("userId", "user_id")
            .csv("createdAt", "created_at")
            .csv("dueAt", "due_at")
            .csv("resolvedAt", "resolved_at")
            .build();

    private final Tickets tickets;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final AuditTrail audit;
    private final Clock clock;

    AdminTicketsController(Tickets tickets, AuditTrail audit, Clock clock, JdbcClient jdbc, AdminTagged tagged) {
        this.jdbc = jdbc;
        this.tagged = tagged;
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
            boolean overdue,
            long version) {

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
                    ticket.overdue(now),
                    ticket.version());
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
    @RequiresPermission(AdminPermission.TICKETS_READ)
    @AdminList("tickets")
    @GetMapping
    AdminTicketPage list(
            @RequestParam(required = false) @Nullable String topic,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer olderThanDays,
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var now = Instant.now(clock);
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "topic",
                        blank(topic),
                        "status",
                        blank(status),
                        "before",
                        olderThanDays == null ? null : Timestamp.from(now.minus(Duration.ofDays(olderThanDays))),
                        "user",
                        user,
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                Tickets::ticket);
        return new AdminTicketPage(
                page.rows().stream().map(ticket -> AdminTicket.of(ticket, now)).toList(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.TICKETS_READ)
    @GetMapping("/{id}")
    AdminTicketThread get(@PathVariable UUID id) {
        var ticket = tickets.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such ticket."));
        return thread(ticket);
    }

    /** Answers the ticket: emailed to its address with its number in the subject. Audited. */
    @RequiresPermission(value = AdminPermission.TICKETS_REPLY, record = "ticket")
    @PostMapping("/{id}/answer")
    AdminTicketThread answer(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminAnswer request) {
        return thread(tickets.answer(id, request.message(), request.status(), audit.admin(admin)));
    }

    /** Changes the ticket's status, with a reason. Audited. */
    @RequiresPermission(value = AdminPermission.TICKETS_REPLY, record = "ticket")
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
