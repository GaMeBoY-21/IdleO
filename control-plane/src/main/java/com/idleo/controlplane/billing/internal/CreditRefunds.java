package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.billing.MoneyTickets;
import com.idleo.controlplane.identity.AuditTrail;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds of bought credit (ADR 0039), only ever to the payments it was bought with, never to another account or
 * method; free credit is never paid out.
 *
 * <ol>
 *   <li>The renter asks ({@link #request}): a {@code money} ticket opens, and the request waits for an admin.
 *   <li>An admin approves an amount ({@link #approve}), at most the bought credit not held for a running session. In
 *       one transaction the credit leaves the renter's bought lots, newest first, for {@code refunds_pending}, with the
 *       GST it carried in per_topup no longer owed, and one part a payment is written down.
 *   <li>Each part goes to Razorpay's Refunds API ({@link #send}), keyed by our receipt, so sending it again refunds
 *       nothing twice. Razorpay answers {@code processed}, {@code pending} or {@code failed}; a pending one is settled by
 *       its {@code refund.processed} or {@code refund.failed} webhook.
 *   <li>Processed: the part leaves {@code refunds_pending} for {@code gateway_clearing} (Razorpay pays it back), and a
 *       credit note goes against the top-up's document. Failed: the credit, and its GST, come back to the lot.
 * </ol>
 *
 * <p>Deleting an account refunds what's left the same way ({@link #forDeletion}), and the deletion waits until it's
 * back with the person ({@link #blockers}).
 */
@Service
class CreditRefunds {

    static final String APPROVE_REF = "credit_refund";
    static final String PROCESSED_REF = "credit_refund_processed";
    static final String FAILED_REF = "credit_refund_failed";

    private static final Logger log = LoggerFactory.getLogger(CreditRefunds.class);

    private final Ledger ledger;
    private final RazorpayClient razorpay;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final Gst gst;
    private final Invoices invoices;
    private final MoneyTickets tickets;
    private final AuditTrail audit;
    private final PaymentMetrics metrics;

    CreditRefunds(
            Ledger ledger,
            RazorpayClient razorpay,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            Gst gst,
            Invoices invoices,
            MoneyTickets tickets,
            AuditTrail audit,
            PaymentMetrics metrics) {
        this.ledger = ledger;
        this.razorpay = razorpay;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.gst = gst;
        this.invoices = invoices;
        this.tickets = tickets;
        this.audit = audit;
        this.metrics = metrics;
    }

    record Refund(
            UUID id,
            UUID userId,
            String cause,
            @Nullable Paise requested,
            @Nullable Paise approved,
            @Nullable String note,
            @Nullable UUID ticketId,
            @Nullable Long ticketNumber,
            String status,
            @Nullable String decidedBy,
            @Nullable String decision,
            Instant requestedAt,
            @Nullable Instant decidedAt,
            @Nullable Instant finishedAt) {}

    record Part(
            UUID id,
            UUID refundId,
            UUID topUpId,
            String paymentId,
            UUID lotId,
            Paise amount,
            String receipt,
            @Nullable String razorpayRefundId,
            String status,
            @Nullable String failureReason) {}

    /** The bought credit a renter can have back now: what's in their bought lots and not held for a session. */
    Paise refundable(UUID userId) {
        var bought = bought(userId);
        var available = jdbc.sql("select balance_paise - reserved_paise from billing.wallets where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .map(Paise::of)
                .orElse(Paise.ZERO);
        return bought.isGreaterThan(available) ? available : bought;
    }

    private Paise bought(UUID userId) {
        return Paise.of(jdbc.sql("""
                        select coalesce(sum(remaining_paise), 0) from billing.credit_lots
                        where user_id = :userId and kind = 'purchased'
                        """).param("userId", userId).query(Long.class).single());
    }

    /** The renter asks for {@code amount} (null: all they can) of their bought credit back: a money ticket opens. */
    Refund request(UUID userId, @Nullable Paise amount, @Nullable String note) {
        if (open(userId).isPresent()) {
            throw ApiProblem.of(Problem.REFUND_ALREADY_REQUESTED, "Your last refund request is still on its way.");
        }
        var refundable = refundable(userId);
        if (!refundable.isPositive()) {
            throw ApiProblem.of(Problem.NOTHING_TO_REFUND, "You have no bought credit to refund just now.");
        }
        if (amount != null && amount.isGreaterThan(refundable)) {
            throw ApiProblem.invalid(
                    "amountPaise",
                    "is more than the " + Rupees.format(refundable.value())
                            + " of bought credit you can have back now.");
        }
        var asked = amount == null
                ? "all my unused bought credit (" + Rupees.format(refundable.value()) + " now)"
                : Rupees.format(amount.value());
        var ticket = tickets.open(
                userId,
                "Refund unused credit: " + asked + ", to where I paid from."
                        + (note == null || note.isBlank() ? "" : "\n\n" + note.strip()));
        var id = ids.newId();
        jdbc.sql("""
                        insert into billing.credit_refunds
                            (id, user_id, cause, requested_paise, note, ticket_id, ticket_number, status, requested_at)
                        values (:id, :userId, 'request', :amount, :note, :ticketId, :ticketNumber, 'requested', :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("amount", amount == null ? null : amount.value())
                .param("note", note == null || note.isBlank() ? null : note.strip())
                .param("ticketId", ticket.id())
                .param("ticketNumber", ticket.number())
                .param("now", Timestamp.from(now()))
                .update();
        return find(id).orElseThrow();
    }

    /**
     * An admin approves {@code amount} of a request: at most what the renter can have back now. The credit is
     * reserved for the refund at once, and the parts are sent to Razorpay.
     */
    Refund approve(UUID refundId, Paise amount, String reason, AuditTrail.Actor actor) {
        var approved = Objects.requireNonNull(transactions.execute(status -> {
            var refund = lock(refundId);
            if (!refund.status().equals("requested")) {
                throw new IllegalArgumentException("refund " + refundId + " is " + refund.status() + ", not waiting");
            }
            move(refund, amount, actor.label(), reason);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "refund.approve",
                    "user",
                    refund.userId().toString(),
                    reason.strip(),
                    Map.of(),
                    Map.of("refundId", refundId.toString(), "amountPaise", amount.value())));
            return find(refundId).orElseThrow();
        }));
        send();
        return find(approved.id()).orElseThrow();
    }

    /** An admin declines a request, with why; nothing moves. */
    Refund decline(UUID refundId, String reason, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var refund = lock(refundId);
            if (!refund.status().equals("requested")) {
                throw new IllegalArgumentException("refund " + refundId + " is " + refund.status() + ", not waiting");
            }
            jdbc.sql("""
                            update billing.credit_refunds
                            set status = 'declined', decided_by = :by, decision = :reason, decided_at = :now,
                                finished_at = :now
                            where id = :id
                            """)
                    .param("by", actor.label())
                    .param("reason", reason.strip())
                    .param("now", Timestamp.from(now()))
                    .param("id", refundId)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "refund.decline",
                    "user",
                    refund.userId().toString(),
                    reason.strip(),
                    Map.of(),
                    Map.of("refundId", refundId.toString())));
            return find(refundId).orElseThrow();
        }));
    }

    /**
     * Deleting an account (ADR 0037, ADR 0039): what's left of the person's bought credit goes back to where it was
     * paid from, approved by the deletion itself. Once a deletion: a refund already open, or nothing to refund, starts
     * nothing.
     */
    void forDeletion(UUID userId) {
        var open = open(userId);
        if ((open.isPresent() && !open.get().status().equals("requested")) || lastDeletionFailed(userId)) {
            return;
        }
        var made = transactions.execute(status -> {
            ledger.lockWallet(userId);
            var amount = refundable(userId);
            if (!amount.isPositive()) {
                return false;
            }
            if (open.isPresent()) {
                // A request still waiting for an admin: the deletion approves it, for everything that's left.
                move(
                        lock(open.get().id()),
                        amount,
                        "deletion",
                        "account deletion: bought credit back to where it was paid from");
                return true;
            }
            var id = ids.newId();
            jdbc.sql("""
                            insert into billing.credit_refunds (id, user_id, cause, status, requested_at)
                            values (:id, :userId, 'deletion', 'requested', :now)
                            """)
                    .param("id", id)
                    .param("userId", userId)
                    .param("now", Timestamp.from(now()))
                    .update();
            move(lock(id), amount, "deletion", "account deletion: bought credit back to where it was paid from");
            return true;
        });
        if (Boolean.TRUE.equals(made)) {
            send();
        }
    }

    /** What stops an account's deletion until its money is back with the person. */
    List<com.idleo.controlplane.PersonalData.Blocker> blockers(UUID userId) {
        var blockers = new ArrayList<com.idleo.controlplane.PersonalData.Blocker>();
        var open = open(userId);
        if (open.isPresent() && open.get().status().equals("refunding")) {
            var pending = pending(userId);
            blockers.add(new com.idleo.controlplane.PersonalData.Blocker(
                    "refund-in-progress",
                    Rupees.format(pending.value()) + " of credit you bought is on its way back to where you paid from. "
                            + "The account is deleted once it's there.",
                    pending.value()));
        } else if (lastDeletionFailed(userId) && bought(userId).isPositive()) {
            blockers.add(new com.idleo.controlplane.PersonalData.Blocker(
                    "refund-failed",
                    "We couldn't send " + Rupees.format(bought(userId).value()) + " of credit you bought back to where "
                            + "you paid from. Write to us and we'll sort it out before the account is deleted.",
                    bought(userId).value()));
        }
        return blockers;
    }

    /** The credit leaves the renter's bought lots, newest first, for the refund; one part a payment. */
    private void move(Refund refund, Paise amount, String by, String reason) {
        var userId = refund.userId();
        ledger.lockWallet(userId);
        var refundable = refundable(userId);
        if (!amount.isPositive() || amount.isGreaterThan(refundable)) {
            throw new IllegalArgumentException(
                    "the renter can have back at most " + refundable.value() + " paise of bought credit now");
        }
        var lots = ledger.spendable(userId).stream()
                .filter(lot -> lot.kind() == CreditLot.Kind.PURCHASED)
                .sorted(Comparator.comparing(CreditLot::createdAt).reversed().thenComparing(CreditLot::id))
                .toList();
        var draws = Ledger.drawFrom(userId, lots, amount);
        var entries = new ArrayList<LedgerEntry>(draws);
        entries.add(LedgerEntry.credit(Account.REFUNDS_PENDING, userId, amount));
        var tax = Paise.ZERO;
        for (var draw : draws) {
            var lot = ledger.lot(Objects.requireNonNull(draw.lotId()));
            var riding = gst.riding(lot, draw.amount(), true);
            if (riding.isPositive()) {
                entries.add(new LedgerEntry(Account.GST_ON_UNUSED_CREDIT, null, riding, Direction.CREDIT, lot.id()));
                tax = tax.plus(riding);
            }
        }
        if (tax.isPositive()) {
            entries.addAll(gst.owed(userId, tax, false));
        }
        ledger.post(
                new LedgerTransaction(derived(APPROVE_REF, refund.id().toString()), APPROVE_REF, refund.id(), entries));
        var now = now();
        for (var draw : draws) {
            var lot = ledger.lot(Objects.requireNonNull(draw.lotId()));
            var topUp = jdbc.sql("select id, payment_id from billing.topups where id = :id")
                    .param("id", lot.sourceId())
                    .query((rs, n) -> new String[] {rs.getString("id"), rs.getString("payment_id")})
                    .single();
            var partId = ids.newId();
            jdbc.sql("""
                            insert into billing.refund_parts
                                (id, refund_id, topup_id, payment_id, lot_id, amount_paise, receipt, status, created_at,
                                 updated_at)
                            values (:id, :refundId, :topupId::uuid, :paymentId, :lotId, :amount, :receipt, 'sending', :now,
                                    :now)
                            """)
                    .param("id", partId)
                    .param("refundId", refund.id())
                    .param("topupId", topUp[0])
                    .param("paymentId", topUp[1])
                    .param("lotId", lot.id())
                    .param("amount", draw.amount().value())
                    .param("receipt", "rf_" + partId.toString().replace("-", ""))
                    .param("now", Timestamp.from(now))
                    .update();
        }
        jdbc.sql("""
                        update billing.credit_refunds
                        set status = 'refunding', approved_paise = :amount, decided_by = :by, decision = :reason,
                            decided_at = :now
                        where id = :id
                        """)
                .param("amount", amount.value())
                .param("by", by)
                .param("reason", reason.strip())
                .param("now", Timestamp.from(now))
                .param("id", refund.id())
                .update();
    }

    /**
     * Sends every part Razorpay hasn't accepted yet: after an approval, and every minute for any that couldn't be sent
     * (Razorpay unreachable). A part sent before whose answer was lost is found by its receipt, never refunded again.
     */
    @Scheduled(fixedDelayString = "${billing.refunds.send-every}")
    void send() {
        var sending = jdbc.sql("select * from billing.refund_parts where status = 'sending' order by created_at")
                .query(CreditRefunds::part)
                .list();
        for (var part : sending) {
            RazorpayClient.Refund answer;
            try {
                answer = razorpay.refund(
                        part.paymentId(),
                        part.amount().value(),
                        part.receipt(),
                        Map.of("refund_id", part.refundId().toString()));
            } catch (RazorpayClient.Refused refused) {
                var earlier = existing(part);
                if (earlier.isEmpty()) {
                    log.error("Razorpay refused refund part {}: {}", part.id(), refused.getMessage());
                    failed(part.id(), null, refused.getMessage());
                    continue;
                }
                answer = earlier.get();
            } catch (RazorpayClient.Unreachable unreachable) {
                log.warn("Refund part {} couldn't be sent: {}; trying again", part.id(), unreachable.getMessage());
                continue;
            }
            settle(part.id(), answer);
        }
    }

    private Optional<RazorpayClient.Refund> existing(Part part) {
        try {
            return razorpay.refundsOf(part.paymentId()).stream()
                    .filter(refund -> part.receipt().equals(refund.receipt()))
                    .findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Razorpay said where a part's refund stands: processed, pending (wait for its webhook) or failed. */
    void settle(UUID partId, RazorpayClient.Refund refund) {
        switch (refund.status()) {
            case "processed" -> processed(partId, refund.id());
            case "failed" -> failed(partId, refund.id(), "Razorpay couldn't refund this payment");
            default ->
                jdbc.sql("""
                            update billing.refund_parts
                            set status = 'pending', razorpay_refund_id = :refundId, updated_at = :now
                            where id = :id and status = 'sending'
                            """)
                        .param("refundId", refund.id())
                        .param("now", Timestamp.from(now()))
                        .param("id", partId)
                        .update();
        }
    }

    /** A {@code refund.processed} or {@code refund.failed} webhook, by Razorpay's refund id or our receipt. */
    void fromWebhook(String razorpayRefundId, @Nullable String receipt, String status) {
        var part = jdbc.sql("""
                        select * from billing.refund_parts where razorpay_refund_id = :refundId or receipt = :receipt
                        """)
                .param("refundId", razorpayRefundId)
                .param("receipt", receipt == null ? "" : receipt)
                .query(CreditRefunds::part)
                .optional();
        if (part.isEmpty()) {
            log.warn("Razorpay refund {} isn't one of ours", razorpayRefundId);
            return;
        }
        switch (status) {
            case "processed" -> processed(part.get().id(), razorpayRefundId);
            case "failed" -> failed(part.get().id(), razorpayRefundId, "Razorpay couldn't refund this payment");
            default -> {}
        }
    }

    /** The part is back with the person: out of {@code refunds_pending}, and a credit note against its top-up. */
    private void processed(UUID partId, String razorpayRefundId) {
        transactions.executeWithoutResult(status -> {
            var part = lockPart(partId);
            if (part.status().equals("processed") || part.status().equals("failed")) {
                return;
            }
            var refund = find(part.refundId()).orElseThrow();
            ledger.post(new LedgerTransaction(
                    derived(PROCESSED_REF, partId.toString()),
                    PROCESSED_REF,
                    partId,
                    List.of(
                            LedgerEntry.debit(Account.REFUNDS_PENDING, refund.userId(), part.amount()),
                            LedgerEntry.credit(Account.GATEWAY_CLEARING, null, part.amount()))));
            jdbc.sql("""
                            update billing.refund_parts
                            set status = 'processed', razorpay_refund_id = :refundId, updated_at = :now
                            where id = :id
                            """)
                    .param("refundId", razorpayRefundId)
                    .param("now", Timestamp.from(now()))
                    .param("id", partId)
                    .update();
            jdbc.sql("select id from billing.invoices where topup_id = :topUpId and kind in ('tax_invoice', 'receipt')")
                    .param("topUpId", part.topUpId())
                    .query(UUID.class)
                    .optional()
                    .ifPresent(invoice ->
                            invoices.creditNote(invoice, part.amount(), "credit refunded to where it was paid from"));
            finish(refund.id());
            metrics.refunded();
        });
    }

    /** The part couldn't be refunded: its credit, and the GST it carried, come back to the lot it left. */
    private void failed(UUID partId, @Nullable String razorpayRefundId, String reason) {
        transactions.executeWithoutResult(status -> {
            var part = lockPart(partId);
            if (part.status().equals("processed") || part.status().equals("failed")) {
                return;
            }
            var refund = find(part.refundId()).orElseThrow();
            ledger.lockWallet(refund.userId());
            var lot = ledger.lot(part.lotId());
            var entries = new ArrayList<LedgerEntry>(List.of(
                    LedgerEntry.debit(Account.REFUNDS_PENDING, refund.userId(), part.amount()),
                    LedgerEntry.creditLot(Account.RENTER_PURCHASED, refund.userId(), part.amount(), lot.id())));
            var riding = gst.riding(lot, part.amount(), false);
            if (riding.isPositive()) {
                entries.add(new LedgerEntry(Account.GST_ON_UNUSED_CREDIT, null, riding, Direction.DEBIT, lot.id()));
                entries.addAll(gst.owed(refund.userId(), riding, true));
            }
            ledger.post(new LedgerTransaction(derived(FAILED_REF, partId.toString()), FAILED_REF, partId, entries));
            jdbc.sql("""
                            update billing.refund_parts
                            set status = 'failed', razorpay_refund_id = coalesce(:refundId, razorpay_refund_id),
                                failure_reason = :reason, updated_at = :now
                            where id = :id
                            """)
                    .param("refundId", razorpayRefundId)
                    .param("reason", reason)
                    .param("now", Timestamp.from(now()))
                    .param("id", partId)
                    .update();
            finish(refund.id());
            metrics.refundFailed();
            log.warn("Refund part {} failed: {}", partId, reason);
        });
    }

    /** Once no part is waiting: refunded if every part was, else failed. */
    private void finish(UUID refundId) {
        var states = jdbc.sql("select status from billing.refund_parts where refund_id = :id")
                .param("id", refundId)
                .query(String.class)
                .list();
        if (states.stream().anyMatch(state -> state.equals("sending") || state.equals("pending"))) {
            return;
        }
        jdbc.sql("update billing.credit_refunds set status = :status, finished_at = :now where id = :id")
                .param("status", states.stream().allMatch("processed"::equals) ? "refunded" : "failed")
                .param("now", Timestamp.from(now()))
                .param("id", refundId)
                .update();
    }

    /** What's on its way back to the person now. */
    Paise pending(UUID userId) {
        return Paise.of(jdbc.sql("""
                        select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries where account = 'refunds_pending' and owner_id = :userId
                        """).param("userId", userId).query(Long.class).single());
    }

    Optional<Refund> open(UUID userId) {
        return jdbc.sql("""
                        select * from billing.credit_refunds
                        where user_id = :userId and status in ('requested', 'refunding')
                        order by requested_at desc limit 1
                        """)
                .param("userId", userId)
                .query(CreditRefunds::refund)
                .optional();
    }

    private boolean lastDeletionFailed(UUID userId) {
        return jdbc.sql("""
                        select coalesce((select status = 'failed' from billing.credit_refunds
                                         where user_id = :userId and cause = 'deletion'
                                         order by requested_at desc limit 1), false)
                        """).param("userId", userId).query(Boolean.class).single();
    }

    Optional<Refund> find(UUID id) {
        return jdbc.sql("select * from billing.credit_refunds where id = :id")
                .param("id", id)
                .query(CreditRefunds::refund)
                .optional();
    }

    List<Refund> ofUser(UUID userId) {
        return jdbc.sql("select * from billing.credit_refunds where user_id = :userId order by requested_at desc")
                .param("userId", userId)
                .query(CreditRefunds::refund)
                .list();
    }

    /** For the admin console: by status (the waiting ones by default), oldest first. */
    List<Refund> list(String status, int limit) {
        return jdbc.sql("""
                        select * from billing.credit_refunds where (:status = 'all' or status = :status)
                        order by requested_at limit :limit
                        """)
                .param("status", status)
                .param("limit", limit)
                .query(CreditRefunds::refund)
                .list();
    }

    List<Part> parts(UUID refundId) {
        return jdbc.sql("select * from billing.refund_parts where refund_id = :id order by created_at, id")
                .param("id", refundId)
                .query(CreditRefunds::part)
                .list();
    }

    private Refund lock(UUID id) {
        return jdbc.sql("select * from billing.credit_refunds where id = :id for update")
                .param("id", id)
                .query(CreditRefunds::refund)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("there's no refund " + id));
    }

    private Part lockPart(UUID id) {
        return jdbc.sql("select * from billing.refund_parts where id = :id for update")
                .param("id", id)
                .query(CreditRefunds::part)
                .single();
    }

    private static UUID derived(String kind, String cause) {
        return UUID.nameUUIDFromBytes((kind + ":" + cause).getBytes(StandardCharsets.UTF_8));
    }

    private static Refund refund(ResultSet rs, int row) throws SQLException {
        var requested = (Long) rs.getObject("requested_paise");
        var approved = (Long) rs.getObject("approved_paise");
        var decided = rs.getTimestamp("decided_at");
        var finished = rs.getTimestamp("finished_at");
        var ticketNumber = (Long) rs.getObject("ticket_number");
        return new Refund(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("cause"),
                requested == null ? null : Paise.of(requested),
                approved == null ? null : Paise.of(approved),
                rs.getString("note"),
                rs.getObject("ticket_id", UUID.class),
                ticketNumber,
                rs.getString("status"),
                rs.getString("decided_by"),
                rs.getString("decision"),
                rs.getTimestamp("requested_at").toInstant(),
                decided == null ? null : decided.toInstant(),
                finished == null ? null : finished.toInstant());
    }

    private static Part part(ResultSet rs, int row) throws SQLException {
        return new Part(
                rs.getObject("id", UUID.class),
                rs.getObject("refund_id", UUID.class),
                rs.getObject("topup_id", UUID.class),
                rs.getString("payment_id"),
                rs.getObject("lot_id", UUID.class),
                Paise.of(rs.getLong("amount_paise")),
                rs.getString("receipt"),
                rs.getString("razorpay_refund_id"),
                rs.getString("status"),
                rs.getString("failure_reason"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
