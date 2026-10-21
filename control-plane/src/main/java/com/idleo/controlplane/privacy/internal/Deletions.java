package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.SecurityLog;
import com.idleo.controlplane.identity.UserDirectory;
import com.idleo.controlplane.notifications.AccountEmails;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asking for an account to be deleted (ADR 0037), every rule in one place:
 *
 * <ul>
 *   <li>only after a sign-in in the last {@link #FRESH} (the token's {@code auth_time}), in a browser, and only when
 *       nothing stops it: a session not ended, a machine running someone's session, earnings not paid out, a report
 *       not answered ({@link PersonalData#blockers});
 *   <li>confirmed by a link emailed to the account's address, which works once, for {@code privacy.confirm-link-ttl},
 *       and is kept only as its SHA-256; asking again sends a new link and the old one stops working;
 *   <li>then a wait of {@code legal.erasure.waiting-period}, which the person cancels with one click from the email
 *       (each link kept as its hash) or in the app, and which nothing shortens but an admin recording a legal order;
 *   <li>a reminder {@code privacy.reminder-before} the end; then {@link Erasure}.
 * </ul>
 *
 * Each request, confirmation and cancellation goes in the security log.
 */
@Service
class Deletions {

    static final Duration FRESH = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final PrivacyProperties properties;
    private final LegalSettings legal;
    private final List<PersonalData> modules;
    private final UserDirectory users;
    private final AccountEmails emails;
    private final SecurityLog security;
    private final AuditTrail audit;
    private final SecureRandom random = new SecureRandom();

    Deletions(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            PrivacyProperties properties,
            LegalSettings legal,
            List<PersonalData> modules,
            UserDirectory users,
            AccountEmails emails,
            SecurityLog security,
            AuditTrail audit) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
        this.legal = legal;
        this.modules = List.copyOf(modules);
        this.users = users;
        this.emails = emails;
        this.security = security;
        this.audit = audit;
    }

    /** A request as its owner, or an admin, sees it. */
    record DeletionRequest(
            UUID id,
            UUID userId,
            String state,
            boolean legalOrder,
            Instant requestedAt,
            @Nullable Instant confirmedAt,
            @Nullable Instant deleteAfter,
            @Nullable Instant cancelledAt,
            int step,
            @Nullable Instant erasedAt) {}

    /** Where the person stands: their open or last request, what stops deletion now, and what they'd lose. */
    record Standing(
            @Nullable DeletionRequest request,
            List<PersonalData.Blocker> blockers,
            List<PersonalData.Loss> losses,
            Duration waitingPeriod) {}

    private Instant now() {
        return Instant.now(clock);
    }

    Standing standing(UUID userId) {
        return new Standing(
                latest(userId).orElse(null),
                blockers(userId),
                losses(userId),
                legal.erasure().waitingPeriod());
    }

    List<PersonalData.Blocker> blockers(UUID userId) {
        var blockers = new ArrayList<PersonalData.Blocker>();
        modules.forEach(module -> blockers.addAll(module.blockers(userId)));
        return blockers;
    }

    private List<PersonalData.Loss> losses(UUID userId) {
        var losses = new ArrayList<PersonalData.Loss>();
        modules.forEach(module -> losses.addAll(module.losses(userId)));
        return losses;
    }

    /** The person's latest request, an expired confirmation marked as such first. */
    Optional<DeletionRequest> latest(UUID userId) {
        jdbc.sql("""
                        update privacy.deletion_requests set state = 'expired', confirm_token_hash = null
                        where user_id = :user and state = 'awaiting_confirmation' and confirm_expires_at <= :now
                        """).param("user", userId).param("now", Timestamp.from(now())).update();
        return jdbc.sql(
                        "select * from privacy.deletion_requests where user_id = :user order by requested_at desc limit 1")
                .param("user", userId)
                .query(Deletions::request)
                .optional();
    }

    static DeletionRequest request(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DeletionRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("state"),
                rs.getBoolean("legal_order"),
                rs.getTimestamp("requested_at").toInstant(),
                instant(rs.getTimestamp("confirmed_at")),
                instant(rs.getTimestamp("delete_after")),
                instant(rs.getTimestamp("cancelled_at")),
                rs.getInt("step"),
                instant(rs.getTimestamp("erased_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Asks to delete the account: emails a confirmation link. The caller has checked the sign-in is fresh. Asked again
     * before it's confirmed, a new link is sent and the old one stops working.
     */
    DeletionRequest request(UUID userId) {
        var blockers = blockers(userId);
        if (!blockers.isEmpty()) {
            throw ApiProblem.of(
                    Problem.DELETION_BLOCKED,
                    "Something has to end before the account can be deleted.",
                    "blockers",
                    blockers);
        }
        var address = users.verifiedEmail(userId)
                .orElseThrow(() -> new IllegalStateException("an account to delete has no verified address"));
        var token = token();
        var now = now();
        var request = transactions.execute(status -> {
            lock(userId);
            var open = latest(userId).filter(Deletions::open);
            if (open.isPresent() && !open.get().state().equals("awaiting_confirmation")) {
                throw ApiProblem.of(
                        Problem.DELETION_ALREADY_REQUESTED, "The account is already waiting to be deleted.");
            }
            UUID id;
            if (open.isPresent()) {
                id = open.get().id();
                jdbc.sql("""
                                update privacy.deletion_requests
                                set confirm_token_hash = :hash, confirm_expires_at = :expires
                                where id = :id
                                """)
                        .param("hash", sha256(token))
                        .param("expires", Timestamp.from(now.plus(properties.confirmLinkTtl())))
                        .param("id", id)
                        .update();
            } else {
                id = ids.newId();
                jdbc.sql("""
                                insert into privacy.deletion_requests
                                    (id, user_id, state, requested_at, confirm_token_hash, confirm_expires_at)
                                values (:id, :user, 'awaiting_confirmation', :now, :hash, :expires)
                                """)
                        .param("id", id)
                        .param("user", userId)
                        .param("now", Timestamp.from(now))
                        .param("hash", sha256(token))
                        .param("expires", Timestamp.from(now.plus(properties.confirmLinkTtl())))
                        .update();
            }
            security.record(SecurityLog.Kind.DELETION_REQUESTED, userId);
            return find(id);
        });
        emails.sendOnce(
                address,
                UUID.nameUUIDFromBytes(
                        ("deletion-confirm:" + request.id() + ":" + token).getBytes(StandardCharsets.UTF_8)),
                "Confirm deleting your account",
                "You asked us to delete your IdleO account. Open this link within "
                        + hours(properties.confirmLinkTtl()) + " to confirm it. Then we wait "
                        + days(legal.erasure().waitingPeriod())
                        + " before deleting anything, and you can cancel until then. If this wasn't you, ignore this "
                        + "email: nothing happens without the link.",
                "/account/delete/confirm/?request=" + request.id() + "&token=" + token);
        return request;
    }

    static boolean open(DeletionRequest request) {
        return List.of("awaiting_confirmation", "scheduled", "erasing").contains(request.state());
    }

    /** Confirms with the emailed link's token: the wait starts. A wrong, used or expired one is one answer. */
    DeletionRequest confirm(UUID userId, UUID requestId, String token) {
        var now = now();
        var cancelToken = token();
        var request = transactions.execute(status -> {
            lock(userId);
            latest(userId);
            int confirmed = jdbc.sql("""
                            update privacy.deletion_requests
                            set state = 'scheduled', confirmed_at = :now, delete_after = :after,
                                confirm_token_hash = null
                            where id = :id and user_id = :user and state = 'awaiting_confirmation'
                              and confirm_token_hash = :hash and confirm_expires_at > :now
                            """)
                    .param("now", Timestamp.from(now))
                    .param("after", Timestamp.from(now.plus(legal.erasure().waitingPeriod())))
                    .param("id", requestId)
                    .param("user", userId)
                    .param("hash", sha256(token))
                    .update();
            if (confirmed == 0) {
                throw ApiProblem.of(Problem.DELETION_LINK_INVALID, "That link can't be used.");
            }
            cancelLink(requestId, cancelToken, now);
            security.record(SecurityLog.Kind.DELETION_CONFIRMED, userId);
            return find(requestId);
        });
        scheduledEmail(request, cancelToken, false);
        return request;
    }

    private void scheduledEmail(DeletionRequest request, String cancelToken, boolean reminder) {
        var address = users.verifiedEmail(request.userId());
        if (address.isEmpty() || request.deleteAfter() == null) {
            return;
        }
        var day = Exports.DAY.format(request.deleteAfter());
        emails.sendOnce(
                address.get(),
                UUID.nameUUIDFromBytes(("deletion-" + (reminder ? "reminder" : "scheduled") + ":" + request.id())
                        .getBytes(StandardCharsets.UTF_8)),
                reminder ? "Your account will be deleted tomorrow" : "Your account will be deleted on " + day,
                (reminder
                                ? "This is your reminder: on " + day + " we delete your IdleO account. "
                                : "We'll delete your IdleO account on " + day + ". ")
                        + "Changed your mind? Cancel with this link, or in Account, Your data. Once it's deleted, "
                        + "it can't be undone.",
                "/account/delete/cancel/?request=" + request.id() + "&token=" + cancelToken);
    }

    /** Cancels the person's waiting deletion from the app. */
    DeletionRequest cancel(UUID userId) {
        return transactions.execute(status -> {
            lock(userId);
            var open = latest(userId)
                    .filter(request ->
                            List.of("awaiting_confirmation", "scheduled").contains(request.state()))
                    // A legal order isn't the person's to cancel.
                    .filter(request -> !request.legalOrder())
                    .orElseThrow(() -> ApiProblem.of(Problem.NO_DELETION_PENDING, "Nothing is waiting to be deleted."));
            return cancelled(open);
        });
    }

    /** Cancels with an emailed link, signed in or not: any link sent for the request works while it's scheduled. */
    DeletionRequest cancelByLink(UUID requestId, String token) {
        return transactions.execute(status -> {
            var request = jdbc.sql("""
                            select r.* from privacy.deletion_requests r
                            join privacy.cancel_links l on l.request_id = r.id
                            where r.id = :id and l.token_hash = :hash
                            for update of r
                            """)
                    .param("id", requestId)
                    .param("hash", sha256(token))
                    .query(Deletions::request)
                    .optional()
                    .orElseThrow(() -> ApiProblem.of(Problem.DELETION_LINK_INVALID, "That link can't be used."));
            if (request.state().equals("cancelled")) {
                return request;
            }
            if (!request.state().equals("scheduled") || request.legalOrder()) {
                throw ApiProblem.of(Problem.NO_DELETION_PENDING, "Nothing is waiting to be deleted.");
            }
            return cancelled(request);
        });
    }

    private DeletionRequest cancelled(DeletionRequest request) {
        jdbc.sql("""
                        update privacy.deletion_requests
                        set state = 'cancelled', cancelled_at = :now, confirm_token_hash = null
                        where id = :id
                        """)
                .param("now", Timestamp.from(now()))
                .param("id", request.id())
                .update();
        security.record(SecurityLog.Kind.DELETION_CANCELLED, request.userId());
        return find(request.id());
    }

    /**
     * An admin records a legal order to erase the account now (ADR 0037): the only way past the wait, audited with its
     * reason. What stops a deletion still stops it.
     */
    DeletionRequest underLegalOrder(UUID userId, String reason, AuditTrail.Actor actor) {
        if (!users.exists(userId)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no such user.");
        }
        return transactions.execute(status -> {
            lock(userId);
            var now = now();
            var open = latest(userId).filter(Deletions::open);
            UUID id;
            if (open.isPresent() && open.get().state().equals("erasing")) {
                return open.get();
            }
            if (open.isPresent()) {
                id = open.get().id();
                jdbc.sql("""
                                update privacy.deletion_requests
                                set state = 'scheduled', legal_order = true, delete_after = :now,
                                    confirmed_at = coalesce(confirmed_at, :now), confirm_token_hash = null
                                where id = :id
                                """).param("now", Timestamp.from(now)).param("id", id).update();
            } else {
                id = ids.newId();
                jdbc.sql("""
                                insert into privacy.deletion_requests
                                    (id, user_id, state, legal_order, requested_at, confirm_expires_at, confirmed_at,
                                     delete_after)
                                values (:id, :user, 'scheduled', true, :now, :now, :now, :now)
                                """)
                        .param("id", id)
                        .param("user", userId)
                        .param("now", Timestamp.from(now))
                        .update();
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "account.erase_legal_order",
                    "user",
                    userId.toString(),
                    reason,
                    open.map(request -> Map.<String, Object>of("state", request.state()))
                            .orElse(null),
                    Map.of("state", "scheduled", "deleteAfter", now.toString())));
            return find(id);
        });
    }

    /** Sends each reminder that's due: one per request, a new cancel link in it. How many it sent. */
    int remindDue() {
        var now = now();
        var due = jdbc.sql("""
                        select * from privacy.deletion_requests
                        where state = 'scheduled' and reminded_at is null and not legal_order
                          and delete_after - make_interval(secs => :before) <= :now and delete_after > :now
                        """)
                .param("before", properties.reminderBefore().toSeconds())
                .param("now", Timestamp.from(now))
                .query(Deletions::request)
                .list();
        int sent = 0;
        for (var request : due) {
            var token = token();
            var claimed = transactions.execute(status -> {
                int marked = jdbc.sql("update privacy.deletion_requests set reminded_at = :now"
                                + " where id = :id and reminded_at is null and state = 'scheduled'")
                        .param("now", Timestamp.from(now))
                        .param("id", request.id())
                        .update();
                if (marked == 1) {
                    cancelLink(request.id(), token, now);
                }
                return marked == 1;
            });
            if (Boolean.TRUE.equals(claimed)) {
                try {
                    scheduledEmail(request, token, true);
                    sent++;
                } catch (RuntimeException refused) {
                    jdbc.sql("update privacy.deletion_requests set reminded_at = null where id = :id")
                            .param("id", request.id())
                            .update();
                    throw refused;
                }
            }
        }
        return sent;
    }

    private void cancelLink(UUID requestId, String token, Instant now) {
        jdbc.sql("insert into privacy.cancel_links (token_hash, request_id, created_at) values (:hash, :id, :now)")
                .param("hash", sha256(token))
                .param("id", requestId)
                .param("now", Timestamp.from(now))
                .update();
    }

    DeletionRequest find(UUID id) {
        return jdbc.sql("select * from privacy.deletion_requests where id = :id")
                .param("id", id)
                .query(Deletions::request)
                .single();
    }

    /** One change at a time to a person's requests. */
    private void lock(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "deletion:" + userId)
                .query((rs, n) -> 1)
                .single();
    }

    private String token() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String days(Duration period) {
        var days = period.toDays();
        return days == 1 ? "1 day" : days + " days";
    }

    static String hours(Duration period) {
        var hours = period.toHours();
        return hours == 1 ? "1 hour" : hours + " hours";
    }
}
