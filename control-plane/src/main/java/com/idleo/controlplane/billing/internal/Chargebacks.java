package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.AccountHolds;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Chargebacks (ADR 0039): Razorpay's {@code payment.dispute.*} webhooks (https://razorpay.com/docs/webhooks/disputes/).
 *
 * <ul>
 *   <li>A dispute on one of our top-ups freezes the renter's bought credit, up to the disputed amount, in {@code
 *       disputes_frozen}: the disputed payment's own lot first, then their other bought lots, the newest first, and
 *       never what a running session holds. Free credit isn't frozen: it was never money. Each later event freezes more
 *       if more is there by then.
 *   <li>Evidence is submitted in Razorpay's dashboard, never from here (docs/runbooks/payments.md); the admin console
 *       shows what we hold and the date it's due ({@code respond_by}).
 *   <li>Once Razorpay says it's final ({@code won}, {@code lost} or {@code closed}), what was lost is its documented
 *       {@code amount_deducted} (for a lost one that says nothing was deducted, the disputed amount; for a won one,
 *       nothing). The loss leaves the frozen credit first and, past it, is {@code chargeback_losses}; the rest of the
 *       freeze goes back to the lots it came from. A loss suspends the account pending review.
 * </ul>
 *
 * Events come more than once and out of order: once final, a dispute never changes again, and each posting's txn_id is
 * named by the dispute (a freeze's also by what was frozen before it), so a repeat posts nothing.
 */
@Service
class Chargebacks {

    static final String FREEZE_REF = "chargeback_freeze";
    static final String SETTLE_REF = "chargeback_settle";

    private static final Set<String> FINAL = Set.of("won", "lost", "closed");
    private static final Logger log = LoggerFactory.getLogger(Chargebacks.class);

    private final Ledger ledger;
    private final Gst gst;
    private final Invoices invoices;
    private final AccountHolds holds;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final com.idleo.controlplane.IdGenerator ids;
    private final Clock clock;
    private final PaymentMetrics metrics;

    Chargebacks(
            Ledger ledger,
            Gst gst,
            Invoices invoices,
            AccountHolds holds,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            com.idleo.controlplane.IdGenerator ids,
            Clock clock,
            PaymentMetrics metrics) {
        this.ledger = ledger;
        this.gst = gst;
        this.invoices = invoices;
        this.holds = holds;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.metrics = metrics;
    }

    record Chargeback(
            UUID id,
            String disputeId,
            String paymentId,
            @Nullable UUID topUpId,
            @Nullable UUID userId,
            Paise amount,
            String currency,
            @Nullable String reasonCode,
            @Nullable String phase,
            String status,
            @Nullable Instant respondBy,
            Paise deducted,
            Paise frozen,
            @Nullable Paise lost,
            @Nullable Paise released,
            @Nullable String outcome,
            Instant openedAt,
            Instant receivedAt,
            @Nullable Instant settledAt) {}

    /** The dispute entity of a {@code payment.dispute.*} event, with only its documented fields. */
    record Dispute(
            String id,
            String paymentId,
            long amount,
            String currency,
            long amountDeducted,
            @Nullable String reasonCode,
            @Nullable String phase,
            String status,
            @Nullable Instant respondBy,
            Instant createdAt) {

        static Dispute of(JsonNode entity) {
            return new Dispute(
                    required(entity, "id"),
                    required(entity, "payment_id"),
                    entity.path("amount").asLong(),
                    required(entity, "currency"),
                    entity.path("amount_deducted").asLong(0),
                    optional(entity, "reason_code"),
                    optional(entity, "phase"),
                    required(entity, "status"),
                    entity.path("respond_by").isNumber()
                            ? Instant.ofEpochSecond(entity.path("respond_by").asLong())
                            : null,
                    entity.path("created_at").isNumber()
                            ? Instant.ofEpochSecond(entity.path("created_at").asLong())
                            : Instant.EPOCH);
        }

        private static String required(JsonNode node, String field) {
            var value = optional(node, field);
            if (value == null) {
                throw new PaymentEvents.Unreadable("a dispute without its " + field);
            }
            return value;
        }

        private static @Nullable String optional(JsonNode node, String field) {
            var value = node.path(field);
            return value.isString() && !value.asString().isEmpty() ? value.asString() : null;
        }
    }

    /** One {@code payment.dispute.*} event's dispute: recorded, frozen and, once final, settled, in one transaction. */
    void fromWebhook(JsonNode disputeEntity) {
        if (!disputeEntity.isObject()) {
            throw new PaymentEvents.Unreadable("a dispute event without its dispute");
        }
        var dispute = Dispute.of(disputeEntity);
        if (dispute.amount() <= 0) {
            throw new PaymentEvents.Unreadable("a dispute of nothing");
        }
        transactions.executeWithoutResult(status -> {
            var chargeback = lock(record(dispute));
            if (chargeback.outcome() != null) {
                log.info(
                        "Dispute {} is settled already ({}); {} changes nothing",
                        dispute.id(),
                        chargeback.outcome(),
                        dispute.status());
                return;
            }
            jdbc.sql("""
                            update billing.chargebacks
                            set status = :status, respond_by = coalesce(:respondBy, respond_by),
                                deducted_paise = :deducted, updated_at = :now
                            where id = :id
                            """)
                    .param("status", dispute.status())
                    .param("respondBy", dispute.respondBy() == null ? null : Timestamp.from(dispute.respondBy()))
                    .param("deducted", dispute.amountDeducted())
                    .param("now", Timestamp.from(now()))
                    .param("id", chargeback.id())
                    .update();
            if (chargeback.userId() == null) {
                log.warn(
                        "Dispute {} is on payment {}, which isn't one of our top-ups",
                        dispute.id(),
                        dispute.paymentId());
                return;
            }
            freeze(lock(chargeback.id()));
            if (FINAL.contains(dispute.status())) {
                settle(lock(chargeback.id()), dispute);
            }
        });
    }

    /** The chargeback's row, made the first time we hear of the dispute. */
    private UUID record(Dispute dispute) {
        var topUp = jdbc.sql("select id, user_id from billing.topups where payment_id = :paymentId")
                .param("paymentId", dispute.paymentId())
                .query((rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)})
                .optional();
        var now = Timestamp.from(now());
        int made = jdbc.sql("""
                        insert into billing.chargebacks
                            (id, dispute_id, payment_id, topup_id, user_id, amount_paise, currency, reason_code, phase,
                             status, respond_by, deducted_paise, frozen_paise, opened_at, received_at, updated_at)
                        values (:id, :disputeId, :paymentId, :topUpId, :userId, :amount, :currency, :reasonCode, :phase,
                                :status, :respondBy, :deducted, 0, :openedAt, :now, :now)
                        on conflict (dispute_id) do nothing
                        """)
                .param("id", ids.newId())
                .param("disputeId", dispute.id())
                .param("paymentId", dispute.paymentId())
                .param("topUpId", topUp.map(row -> row[0]).orElse(null))
                .param("userId", topUp.map(row -> row[1]).orElse(null))
                .param("amount", dispute.amount())
                .param("currency", dispute.currency())
                .param("reasonCode", dispute.reasonCode())
                .param("phase", dispute.phase())
                .param("status", dispute.status())
                .param("respondBy", dispute.respondBy() == null ? null : Timestamp.from(dispute.respondBy()))
                .param("deducted", dispute.amountDeducted())
                .param("openedAt", Timestamp.from(dispute.createdAt()))
                .param("now", now)
                .update();
        if (made > 0 && topUp.isPresent()) {
            metrics.chargeback("opened");
        }
        return jdbc.sql("select id from billing.chargebacks where dispute_id = :disputeId")
                .param("disputeId", dispute.id())
                .query(UUID.class)
                .single();
    }

    /** Freezes what more of the disputed amount the renter's bought credit can cover now. */
    private void freeze(Chargeback chargeback) {
        var userId = Objects.requireNonNull(chargeback.userId());
        var wanted = chargeback.amount().minus(chargeback.frozen());
        if (!wanted.isPositive()) {
            return;
        }
        var wallet = ledger.lockWallet(userId);
        var own = chargeback.topUpId() == null
                ? null
                : ledger.lotOf(TopUps.TOPUP_REF, chargeback.topUpId()).orElse(null);
        var lots = ledger.spendable(userId).stream()
                .filter(lot -> lot.kind() == CreditLot.Kind.PURCHASED)
                .sorted(Comparator.comparing(
                                (CreditLot lot) -> own == null || !lot.id().equals(own.id()))
                        .thenComparing(
                                Comparator.comparing(CreditLot::createdAt).reversed())
                        .thenComparing(CreditLot::id))
                .toList();
        var bought = lots.stream().map(CreditLot::remaining).reduce(Paise.ZERO, Paise::plus);
        var amount = min(min(wanted, bought), wallet.available());
        if (!amount.isPositive()) {
            return;
        }
        var draws = Ledger.drawFrom(userId, lots, amount);
        var entries = new ArrayList<LedgerEntry>(draws);
        entries.add(LedgerEntry.credit(Account.DISPUTES_FROZEN, userId, amount));
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
        ledger.post(new LedgerTransaction(
                derived(FREEZE_REF, chargeback.id() + ":" + chargeback.frozen().value()),
                FREEZE_REF,
                chargeback.id(),
                entries));
        jdbc.sql("update billing.chargebacks set frozen_paise = frozen_paise + :amount where id = :id")
                .param("amount", amount.value())
                .param("id", chargeback.id())
                .update();
        log.warn("Froze {} paise of {}'s credit for dispute {}", amount.value(), userId, chargeback.disputeId());
    }

    /**
     * The dispute is final: the loss leaves the frozen credit, the disputed payment's lot first, and past it is our loss;
     * the rest goes back to its lots, with the GST it carried. A loss suspends the account pending review.
     */
    private void settle(Chargeback chargeback, Dispute dispute) {
        var userId = Objects.requireNonNull(chargeback.userId());
        var lost = Paise.of(
                switch (dispute.status()) {
                    case "won" -> 0;
                    case "lost" -> dispute.amountDeducted() > 0 ? dispute.amountDeducted() : dispute.amount();
                    default -> dispute.amountDeducted();
                });
        var frozen = chargeback.frozen();
        var fromFrozen = min(lost, frozen);
        var shortfall = lost.minus(fromFrozen);
        var own = chargeback.topUpId() == null
                ? null
                : ledger.lotOf(TopUps.TOPUP_REF, chargeback.topUpId()).orElse(null);
        // What the freezes took from each lot, the disputed payment's own first, then as they were frozen.
        var byLot = new ArrayList<>(frozenByLot(userId, chargeback.id()));
        byLot.sort(Comparator.comparing(entry -> own == null || !entry.lotId().equals(own.id())));
        ledger.lockWallet(userId);
        var entries = new ArrayList<LedgerEntry>();
        var lostLeft = fromFrozen;
        var tax = Paise.ZERO;
        var creditNotes = new ArrayList<LotPart>();
        for (var part : byLot) {
            var lose = min(lostLeft, part.amount());
            lostLeft = lostLeft.minus(lose);
            if (lose.isPositive()) {
                creditNotes.add(new LotPart(part.lotId(), lose));
            }
            var back = part.amount().minus(lose);
            if (back.isPositive()) {
                var lot = ledger.lot(part.lotId());
                entries.add(LedgerEntry.creditLot(Account.RENTER_PURCHASED, userId, back, lot.id()));
                var riding = gst.riding(lot, back, false);
                if (riding.isPositive()) {
                    entries.add(new LedgerEntry(Account.GST_ON_UNUSED_CREDIT, null, riding, Direction.DEBIT, lot.id()));
                    tax = tax.plus(riding);
                }
            }
        }
        if (frozen.isPositive()) {
            entries.add(LedgerEntry.debit(Account.DISPUTES_FROZEN, userId, frozen));
        }
        if (tax.isPositive()) {
            entries.addAll(gst.owed(userId, tax, true));
        }
        if (shortfall.isPositive()) {
            entries.add(LedgerEntry.debit(Account.CHARGEBACK_LOSSES, null, shortfall));
        }
        if (lost.isPositive()) {
            entries.add(LedgerEntry.credit(Account.GATEWAY_CLEARING, null, lost));
        }
        if (!entries.isEmpty()) {
            ledger.post(new LedgerTransaction(
                    derived(SETTLE_REF, chargeback.id().toString()), SETTLE_REF, chargeback.id(), entries));
        }
        boolean wasLost = lost.isPositive();
        jdbc.sql("""
                        update billing.chargebacks
                        set outcome = :outcome, lost_paise = :lost, released_paise = :released, frozen_paise = 0,
                            settled_at = :now, updated_at = :now
                        where id = :id
                        """)
                .param("outcome", wasLost ? "lost" : "won")
                .param("lost", lost.value())
                .param("released", frozen.minus(fromFrozen).value())
                .param("now", Timestamp.from(now()))
                .param("id", chargeback.id())
                .update();
        for (var part : creditNotes) {
            var lot = ledger.lot(part.lotId());
            invoices.ofTopUp(lot.sourceId())
                    .ifPresent(invoice -> invoices.creditNote(
                            invoice.id(), part.amount(), "credit taken back by a chargeback on the payment"));
        }
        if (wasLost) {
            try {
                holds.hold(
                        userId,
                        "a chargeback on payment " + chargeback.paymentId() + " was lost; suspended pending review",
                        AuditTrail.Actor.system("chargebacks"));
            } catch (IllegalStateException e) {
                log.info(
                        "Renter {} wasn't suspended for dispute {}: {}",
                        userId,
                        chargeback.disputeId(),
                        e.getMessage());
            }
        }
        metrics.chargeback(wasLost ? "lost" : "won");
        log.warn(
                "Dispute {} settled {}: {} paise lost ({} beyond the frozen credit), {} paise back",
                chargeback.disputeId(),
                wasLost ? "lost" : "won",
                lost.value(),
                shortfall.value(),
                frozen.minus(fromFrozen).value());
    }

    private record LotPart(UUID lotId, Paise amount) {}

    private List<LotPart> frozenByLot(UUID userId, UUID chargebackId) {
        return ledger.drawnBy(userId, chargebackId, List.of(FREEZE_REF)).entrySet().stream()
                .map(entry -> new LotPart(entry.getKey(), entry.getValue()))
                .toList();
    }

    Optional<Chargeback> find(UUID id) {
        return jdbc.sql("select * from billing.chargebacks where id = :id")
                .param("id", id)
                .query(Chargebacks::chargeback)
                .optional();
    }

    List<Chargeback> ofUser(UUID userId) {
        return jdbc.sql("select * from billing.chargebacks where user_id = :userId order by received_at desc")
                .param("userId", userId)
                .query(Chargebacks::chargeback)
                .list();
    }

    /** For the admin console: the open ones by the date evidence is due, or every one, the newest first. */
    List<Chargeback> list(boolean openOnly, int limit) {
        return jdbc.sql(
                        openOnly
                                ? "select * from billing.chargebacks where outcome is null order by respond_by nulls last, id"
                                        + " limit :limit"
                                : "select * from billing.chargebacks order by received_at desc, id desc limit :limit")
                .param("limit", limit)
                .query(Chargebacks::chargeback)
                .list();
    }

    /** The sessions the disputed payment's credit paid for, with what each took from it. */
    List<Spent> spentFrom(Chargeback chargeback) {
        if (chargeback.topUpId() == null || chargeback.userId() == null) {
            return List.of();
        }
        var lot = ledger.lotOf(TopUps.TOPUP_REF, chargeback.topUpId());
        if (lot.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select ref_id, sum(case direction when 'debit' then amount_paise else -amount_paise end) as net
                        from billing.ledger_entries
                        where lot_id = :lotId and owner_id = :userId and account = 'renter_purchased'
                          and ref_type in ('session', 'session_refund')
                        group by ref_id
                        having sum(case direction when 'debit' then amount_paise else -amount_paise end) > 0
                        order by min(created_at)
                        """)
                .param("lotId", lot.get().id())
                .param("userId", chargeback.userId())
                .query((rs, n) -> new Spent(rs.getObject("ref_id", UUID.class), Paise.of(rs.getLong("net"))))
                .list();
    }

    record Spent(UUID sessionId, Paise amount) {}

    private Chargeback lock(UUID id) {
        return jdbc.sql("select * from billing.chargebacks where id = :id for update")
                .param("id", id)
                .query(Chargebacks::chargeback)
                .single();
    }

    private static Paise min(Paise a, Paise b) {
        return a.isGreaterThan(b) ? b : a;
    }

    private static UUID derived(String kind, String cause) {
        return UUID.nameUUIDFromBytes((kind + ":" + cause).getBytes(StandardCharsets.UTF_8));
    }

    private static Chargeback chargeback(ResultSet rs, int row) throws SQLException {
        var respondBy = rs.getTimestamp("respond_by");
        var lost = (Long) rs.getObject("lost_paise");
        var released = (Long) rs.getObject("released_paise");
        var settled = rs.getTimestamp("settled_at");
        return new Chargeback(
                rs.getObject("id", UUID.class),
                rs.getString("dispute_id"),
                rs.getString("payment_id"),
                rs.getObject("topup_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Paise.of(rs.getLong("amount_paise")),
                rs.getString("currency"),
                rs.getString("reason_code"),
                rs.getString("phase"),
                rs.getString("status"),
                respondBy == null ? null : respondBy.toInstant(),
                Paise.of(rs.getLong("deducted_paise")),
                Paise.of(rs.getLong("frozen_paise")),
                lost == null ? null : Paise.of(lost),
                released == null ? null : Paise.of(released),
                rs.getString("outcome"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("received_at").toInstant(),
                settled == null ? null : settled.toInstant());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
