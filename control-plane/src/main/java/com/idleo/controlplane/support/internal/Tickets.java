package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.ContactDetails;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.notifications.AccountEmails;
import com.idleo.controlplane.sessions.SessionOutcomes;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tickets (ADR 0037), every rule in one place: who may name which session or machine, the due date of a privacy
 * request or a grievance, the acknowledgement and every answer emailed with the ticket's number (and the line "Reply
 * here, not by email", since our address takes no replies), and each admin action audited by id only.
 */
@Service
class Tickets {

    private static final Logger log = LoggerFactory.getLogger(Tickets.class);

    /** The topics, as the forms offer them, with their words. */
    static final Map<String, String> TOPICS = new java.util.LinkedHashMap<>();

    static {
        TOPICS.put("session", "A session");
        TOPICS.put("money", "Money and credit");
        TOPICS.put("hosting", "Hosting");
        TOPICS.put("account", "My account");
        TOPICS.put("privacy", "A privacy request");
        TOPICS.put("report", "Report a problem with a host or renter");
        TOPICS.put("grievance", "A grievance");
    }

    /** The topics a due date is set for. */
    static final List<String> DUE = List.of("privacy", "grievance");

    /** A message sent within this of its page opening wasn't written by a person. */
    static final Duration TOO_FAST = Duration.ofSeconds(3);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final LegalSettings legal;
    private final SessionOutcomes sessions;
    private final HostOwners hosts;
    private final AccountEmails emails;
    private final AuditTrail audit;

    Tickets(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            LegalSettings legal,
            SessionOutcomes sessions,
            HostOwners hosts,
            AccountEmails emails,
            AuditTrail audit) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.legal = legal;
        this.sessions = sessions;
        this.hosts = hosts;
        this.emails = emails;
        this.audit = audit;
    }

    record StoredTicket(
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
            @Nullable Instant resolvedAt) {

        boolean overdue(Instant now) {
            return dueAt != null && !status.equals("resolved") && dueAt.isBefore(now);
        }
    }

    record Message(UUID id, String author, String body, Instant createdAt) {}

    record New(
            String topic,
            String message,
            String replyTo,
            @Nullable UUID sessionId,
            @Nullable UUID hostId,
            @Nullable String website,
            long elapsedMs) {}

    private Instant now() {
        return Instant.now(clock);
    }

    /** A new ticket from the Contact page or Account, Help: acknowledged at once by email, with its number. */
    StoredTicket open(@Nullable UUID userId, New request) {
        if ((request.website() != null && !request.website().isBlank()) || request.elapsedMs() < TOO_FAST.toMillis()) {
            throw ApiProblem.of(Problem.MESSAGE_LOOKS_AUTOMATED, "That was sent faster than a person writes.");
        }
        if (!TOPICS.containsKey(request.topic())) {
            throw ApiProblem.invalid("topic", "Choose one of the topics.");
        }
        if (!ContactDetails.isEmailAddress(request.replyTo())) {
            throw ApiProblem.invalid("replyTo", "Give an email address we can answer.");
        }
        if (request.sessionId() != null
                && (userId == null
                        || sessions.of(request.sessionId())
                                .filter(outcome -> outcome.renterId().equals(userId))
                                .isEmpty())) {
            throw ApiProblem.invalid("sessionId", "Choose one of your own sessions.");
        }
        if (request.hostId() != null && (userId == null || !hosts.owns(userId, request.hostId()))) {
            throw ApiProblem.invalid("hostId", "Choose one of your own machines.");
        }
        var now = now();
        var due = DUE.contains(request.topic()) ? now.plus(legal.support().responseDue()) : null;
        var ticket = transactions.execute(status -> {
            var id = ids.newId();
            jdbc.sql("""
                            insert into support.tickets
                                (id, user_id, topic, status, reply_to, session_id, host_id, created_at, updated_at,
                                 due_at)
                            values (:id, :user, :topic, 'open', :replyTo, :session, :host, :now, :now, :due)
                            """)
                    .param("id", id)
                    .param("user", userId)
                    .param("topic", request.topic())
                    .param("replyTo", request.replyTo().strip())
                    .param("session", request.sessionId())
                    .param("host", request.hostId())
                    .param("now", Timestamp.from(now))
                    .param("due", due == null ? null : Timestamp.from(due))
                    .update();
            message(id, "person", null, request.message(), now);
            return find(id).orElseThrow();
        });
        emails.sendOnce(
                ticket.replyTo(),
                messageId("received", ticket.id()),
                "We've got your message (#" + ticket.number() + ")",
                "Thank you. A person reads every message"
                        + (due == null ? "" : ", and we answer this kind within " + due(legal) + " at the latest")
                        + ". We'll email you here. Reply here, not by email: our address takes no replies."
                        + (userId == null
                                ? " To add something, send another message from the Contact page and give the number #"
                                        + ticket.number() + "."
                                : " To add something, open the link."),
                userId == null ? "/" : "/account/help/ticket/?id=" + ticket.id());
        log.info("StoredTicket {} opened ({})", ticket.number(), ticket.topic());
        return ticket;
    }

    static String due(LegalSettings legal) {
        return legal.support().responseDue().toDays() + " days";
    }

    private void message(UUID ticket, String author, @Nullable UUID admin, String body, Instant now) {
        jdbc.sql("""
                        insert into support.messages (id, ticket_id, author, admin_id, body, created_at)
                        values (:id, :ticket, :author, :admin, :body, :now)
                        """)
                .param("id", ids.newId())
                .param("ticket", ticket)
                .param("author", author)
                .param("admin", admin)
                .param("body", body.strip())
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The person adds to their own ticket: it's open again. */
    StoredTicket reply(UUID userId, UUID ticketId, String body) {
        return transactions.execute(status -> {
            var ticket = own(userId, ticketId);
            var now = now();
            message(ticket.id(), "person", null, body, now);
            jdbc.sql("""
                            update support.tickets set status = 'open', resolved_at = null, updated_at = :now
                            where id = :id
                            """)
                    .param("now", Timestamp.from(now))
                    .param("id", ticket.id())
                    .update();
            return find(ticket.id()).orElseThrow();
        });
    }

    /** The person's own ticket; 404 for anyone else's. */
    StoredTicket own(UUID userId, UUID ticketId) {
        return find(ticketId)
                .filter(ticket -> userId.equals(ticket.userId()))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such message."));
    }

    List<StoredTicket> mine(UUID userId) {
        return jdbc.sql("select * from support.tickets where user_id = :user order by created_at desc limit 100")
                .param("user", userId)
                .query(Tickets::ticket)
                .list();
    }

    Optional<StoredTicket> find(UUID id) {
        return jdbc.sql("select * from support.tickets where id = :id")
                .param("id", id)
                .query(Tickets::ticket)
                .optional();
    }

    List<Message> messages(UUID ticketId) {
        return jdbc.sql("""
                        select id, author, body, created_at from support.messages
                        where ticket_id = :ticket order by created_at, id
                        """)
                .param("ticket", ticketId)
                .query((rs, n) -> new Message(
                        rs.getObject("id", UUID.class),
                        rs.getString("author"),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * An admin answers: the answer is emailed to the ticket's address with its number in the subject, and the status
     * becomes {@code status} ({@code waiting} for the person, or {@code resolved}). Audited by id.
     */
    StoredTicket answer(UUID ticketId, String body, String status, AuditTrail.Actor admin) {
        if (!List.of("open", "waiting", "resolved").contains(status)) {
            throw ApiProblem.invalid("status", "open, waiting or resolved.");
        }
        var answered = transactions.execute(tx -> {
            var ticket = find(ticketId).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such ticket."));
            var now = now();
            message(ticket.id(), "admin", admin.userId(), body, now);
            setStatus(ticket, status, now);
            audit.record(new AuditTrail.Entry(
                    admin,
                    "ticket.answer",
                    "ticket",
                    ticket.id().toString(),
                    "answered ticket #" + ticket.number(),
                    Map.of("status", ticket.status()),
                    Map.of("status", status)));
            return find(ticket.id()).orElseThrow();
        });
        var address = answered.replyTo();
        if (address != null && !address.isBlank()) {
            emails.sendOnce(
                    address,
                    messageId("answer:" + answered.updatedAt(), answered.id()),
                    "Re: your message (#" + answered.number() + ")",
                    body.strip() + "\n\nReply here, not by email: our address takes no replies. "
                            + (answered.userId() == null
                                    ? "To answer, send a message from the Contact page and give the number #"
                                            + answered.number() + "."
                                    : "Open the link to answer."),
                    answered.userId() == null ? "/" : "/account/help/ticket/?id=" + answered.id());
        }
        return answered;
    }

    /** An admin changes a ticket's status, with a reason; audited. */
    StoredTicket setStatus(UUID ticketId, String status, String reason, AuditTrail.Actor admin) {
        if (!List.of("open", "waiting", "resolved").contains(status)) {
            throw ApiProblem.invalid("status", "open, waiting or resolved.");
        }
        return transactions.execute(tx -> {
            var ticket = find(ticketId).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such ticket."));
            setStatus(ticket, status, now());
            audit.record(new AuditTrail.Entry(
                    admin,
                    "ticket.status",
                    "ticket",
                    ticket.id().toString(),
                    reason,
                    Map.of("status", ticket.status()),
                    Map.of("status", status)));
            return find(ticket.id()).orElseThrow();
        });
    }

    private void setStatus(StoredTicket ticket, String status, Instant now) {
        jdbc.sql("""
                        update support.tickets
                        set status = :status, updated_at = :now,
                            resolved_at = case when :status = 'resolved' then coalesce(resolved_at, :now) end
                        where id = :id
                        """)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .param("id", ticket.id())
                .update();
    }

    /** For the admin list: by due date, overdue first, then the oldest; filtered by topic, status and age. */
    List<StoredTicket> list(
            @Nullable String topic, @Nullable String status, @Nullable Integer olderThanDays, int limit, int offset) {
        return jdbc.sql("""
                        select * from support.tickets
                        where (cast(:topic as text) is null or topic = cast(:topic as text))
                          and (cast(:status as text) is null or status = cast(:status as text))
                          and (cast(:before as timestamptz) is null or created_at < cast(:before as timestamptz))
                        order by (status = 'resolved'), due_at asc nulls last, created_at asc
                        limit :limit offset :offset
                        """)
                .param("topic", topic)
                .param("status", status)
                .param(
                        "before",
                        olderThanDays == null ? null : Timestamp.from(now().minus(Duration.ofDays(olderThanDays))))
                .param("limit", limit)
                .param("offset", offset)
                .query(Tickets::ticket)
                .list();
    }

    static StoredTicket ticket(ResultSet rs, int row) throws SQLException {
        return new StoredTicket(
                rs.getObject("id", UUID.class),
                rs.getLong("number"),
                rs.getObject("user_id", UUID.class),
                rs.getString("topic"),
                rs.getString("status"),
                rs.getString("reply_to"),
                rs.getObject("session_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("due_at")),
                instant(rs.getTimestamp("resolved_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static UUID messageId(String what, UUID ticket) {
        return UUID.nameUUIDFromBytes(("ticket-" + what + ":" + ticket).getBytes(StandardCharsets.UTF_8));
    }
}
