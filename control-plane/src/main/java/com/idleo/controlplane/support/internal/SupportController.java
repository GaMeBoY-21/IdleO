package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Messages to support, as the person sees them (ADR 0037). See the support module's documentation. */
@RestController
@ConditionalOnWebApplication
class SupportController {

    private final Tickets tickets;
    private final LegalSettings legal;

    SupportController(Tickets tickets, LegalSettings legal) {
        this.tickets = tickets;
        this.legal = legal;
    }

    /**
     * A message: its topic ({@code session}, {@code money}, {@code hosting}, {@code account}, {@code privacy}, {@code
     * report} or {@code grievance}), the words (plain text), where to answer, optionally one of the person's own
     * sessions or machines; {@code website} is a field people never see, and {@code elapsedMs} how long the page was
     * open.
     */
    record NewTicket(
            @NotBlank String topic,
            @NotBlank @Size(max = 5000) String message,
            @NotBlank @Size(max = 254) String replyTo,
            @Nullable UUID sessionId,
            @Nullable UUID hostId,
            @Nullable @Size(max = 200) String website,
            @PositiveOrZero long elapsedMs) {}

    /** A ticket as its person sees it: number, topic, status, dates, what it's about, and when we answer by. */
    record SupportTicket(
            UUID id,
            long number,
            String topic,
            String status,
            @Nullable UUID sessionId,
            @Nullable UUID hostId,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant dueAt) {

        static SupportTicket of(Tickets.StoredTicket ticket) {
            return new SupportTicket(
                    ticket.id(),
                    ticket.number(),
                    ticket.topic(),
                    ticket.status(),
                    ticket.sessionId(),
                    ticket.hostId(),
                    ticket.createdAt(),
                    ticket.updatedAt(),
                    ticket.dueAt());
        }
    }

    record SupportMessage(String author, String body, Instant createdAt) {}

    record SupportThread(SupportTicket ticket, List<SupportMessage> messages) {}

    record SupportTickets(List<SupportTicket> tickets) {}

    record Reply(@NotBlank @Size(max = 5000) String message) {}

    /** Sends a message to support, signed in or not: 202 with its number; acknowledged at once by email. */
    @OpenToWaitingUsers
    @OpenToHeldAccounts
    @PostMapping("/v1/support/tickets")
    ResponseEntity<SupportTicket> open(
            @AuthenticationPrincipal @Nullable CurrentUser user, @Valid @RequestBody NewTicket request) {
        var ticket = tickets.open(
                user == null ? null : user.id(),
                new Tickets.New(
                        request.topic(),
                        request.message(),
                        request.replyTo(),
                        request.sessionId(),
                        request.hostId(),
                        request.website(),
                        request.elapsedMs()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/v1/support/tickets/" + ticket.id()))
                .body(SupportTicket.of(ticket));
    }

    @OpenToWaitingUsers
    @OpenToHeldAccounts
    @GetMapping("/v1/support/tickets")
    SupportTickets mine(@AuthenticationPrincipal CurrentUser user) {
        return new SupportTickets(
                tickets.mine(user.id()).stream().map(SupportTicket::of).toList());
    }

    @OpenToWaitingUsers
    @OpenToHeldAccounts
    @GetMapping("/v1/support/tickets/{id}")
    SupportThread thread(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        var ticket = tickets.own(user.id(), id);
        return thread(ticket);
    }

    /** The person adds to their own ticket, which is open again. */
    @OpenToWaitingUsers
    @OpenToHeldAccounts
    @PostMapping("/v1/support/tickets/{id}/messages")
    SupportThread reply(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @Valid @RequestBody Reply request) {
        return thread(tickets.reply(user.id(), id, request.message()));
    }

    private SupportThread thread(Tickets.StoredTicket ticket) {
        return new SupportThread(
                SupportTicket.of(ticket),
                tickets.messages(ticket.id()).stream()
                        .map(message -> new SupportMessage(message.author(), message.body(), message.createdAt()))
                        .toList());
    }

    record Topic(String code, String label) {}

    record GrievanceOfficer(String name, String email, String postalAddress) {}

    /** What the Contact and Privacy pages show: the topics, the grievance officer, and how soon we answer. */
    record PublicContact(List<Topic> topics, GrievanceOfficer grievanceOfficer, long responseDays) {}

    @GetMapping("/v1/public/contact")
    ResponseEntity<PublicContact> contact() {
        var grievance = legal.grievance();
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(10))
                        .cachePublic())
                .body(new PublicContact(
                        Tickets.TOPICS.entrySet().stream()
                                .map(topic -> new Topic(topic.getKey(), topic.getValue()))
                                .toList(),
                        new GrievanceOfficer(grievance.name(), grievance.email(), grievance.postalAddress()),
                        legal.support().responseDue().toDays()));
    }
}
