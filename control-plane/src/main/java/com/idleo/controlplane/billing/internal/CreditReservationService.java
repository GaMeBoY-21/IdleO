package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code billing.reservations} and {@code billing.reservation_changes} (a test checks). The
 * wallet's {@code reserved_paise} moves with every hold, through {@link Ledger}, in the same transaction, under
 * the wallet's lock. Every change to a hold's amount is recorded, with its reason (ADR 0017).
 */
@Service
class CreditReservationService implements CreditReservations {

    private final Ledger ledger;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final Gst gst;

    CreditReservationService(Ledger ledger, JdbcClient jdbc, IdGenerator ids, Clock clock, Gst gst) {
        this.gst = gst;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    /** A session's active hold. */
    record ActiveHold(UUID id, UUID userId, Paise amount, Paise committed) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID userId, UUID sessionId, Paise amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a hold must be above zero");
        }
        var wallet = ledger.lockWallet(userId);
        if (held(sessionId).isPresent()) {
            return true;
        }
        if (amount.isGreaterThan(wallet.available())) {
            return false;
        }
        var id = ids.newId();
        jdbc.sql("""
                        insert into billing.reservations (id, user_id, session_id, amount_paise, status, created_at)
                        values (:id, :userId, :sessionId, :amount, 'active', :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("amount", amount.value())
                .param("now", Timestamp.from(now()))
                .update();
        change(id, sessionId, "reserved", Paise.ZERO, amount, null);
        ledger.adjustReserved(userId, amount);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(UUID sessionId) {
        var hold = active(sessionId);
        if (hold.isEmpty()) {
            return false;
        }
        // The wallet first, as every posting does, so a release and a hold can't deadlock.
        ledger.lockWallet(hold.get().userId());
        int released = jdbc.sql("""
                        update billing.reservations set status = 'released', released_at = :now
                        where id = :id and status = 'active'
                        """)
                .param("now", Timestamp.from(now()))
                .param("id", hold.get().id())
                .update();
        if (released == 0) {
            return false;
        }
        change(hold.get().id(), sessionId, "released", hold.get().amount(), Paise.ZERO, null);
        ledger.adjustReserved(hold.get().userId(), hold.get().amount().negate());
        return true;
    }

    /**
     * For a settlement, under the wallet's lock: the hold shrinks by {@code charged}, never below zero, and so
     * does what's committed against it. Returns false, changing nothing, if this settlement already shrank it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean shrinkForSettlement(UUID sessionId, Paise charged, UUID txnId) {
        if (jdbc.sql("select exists (select 1 from billing.reservation_changes where txn_id = :txnId)")
                .param("txnId", txnId)
                .query(Boolean.class)
                .single()) {
            return false;
        }
        var hold = active(sessionId);
        if (hold.isEmpty()) {
            return true;
        }
        var before = hold.get().amount();
        var after = charged.isGreaterThan(before) ? Paise.ZERO : before.minus(charged);
        var committed = charged.isGreaterThan(hold.get().committed())
                ? Paise.ZERO
                : hold.get().committed().minus(charged);
        update(hold.get().id(), after, committed);
        change(hold.get().id(), sessionId, "settled", before, after, txnId);
        ledger.adjustReserved(hold.get().userId(), after.minus(before));
        return true;
    }

    /**
     * After a settlement, under the wallet's lock: the hold grows back towards {@code target}, as far as the
     * renter's available balance allows. It never shrinks here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void topUp(UUID sessionId, Paise target) {
        var hold = active(sessionId);
        if (hold.isEmpty() || !target.isGreaterThan(hold.get().amount())) {
            return;
        }
        var wallet = ledger.lockWallet(hold.get().userId());
        var wanted = target.minus(hold.get().amount());
        var added = wanted.isGreaterThan(wallet.available()) ? wallet.available() : wanted;
        if (!added.isPositive()) {
            return;
        }
        var after = hold.get().amount().plus(added);
        update(hold.get().id(), after, hold.get().committed());
        change(hold.get().id(), sessionId, "topped_up", hold.get().amount(), after, null);
        ledger.adjustReserved(hold.get().userId(), added);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCommitted(UUID sessionId, Paise committed, Instant countedAt) {
        if (committed.isNegative()) {
            throw new IllegalArgumentException("committed use can't be negative");
        }
        // Written on every count, even unchanged: the minutes it covers move on with countedAt.
        jdbc.sql("""
                        update billing.reservations set committed_paise = :committed, committed_as_of = :countedAt
                        where session_id = :sessionId and status = 'active'
                        """)
                .param("committed", committed.value())
                .param("countedAt", Timestamp.from(countedAt))
                .param("sessionId", sessionId)
                .update();
    }

    @Override
    public Optional<Uncommitted> uncommitted(UUID sessionId) {
        return jdbc.sql("""
                        select amount_paise - committed_paise as left_paise, committed_as_of from billing.reservations
                        where session_id = :sessionId and status = 'active'
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Uncommitted(
                        Paise.of(rs.getLong("left_paise")),
                        rs.getTimestamp("committed_as_of") == null
                                ? null
                                : rs.getTimestamp("committed_as_of").toInstant()))
                .optional();
    }

    @Override
    public Paise renterCost(Paise net) {
        return gst.renterCost(net);
    }

    @Override
    public int gstPercent() {
        return gst.gstPercent();
    }

    @Override
    public Paise available(UUID userId) {
        return jdbc.sql("select balance_paise - reserved_paise from billing.wallets where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .map(Paise::of)
                .orElse(Paise.ZERO);
    }

    @Override
    public Optional<Paise> held(UUID sessionId) {
        return active(sessionId).map(ActiveHold::amount);
    }

    Optional<ActiveHold> active(UUID sessionId) {
        return jdbc.sql("""
                        select id, user_id, amount_paise, committed_paise from billing.reservations
                        where session_id = :sessionId and status = 'active'
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new ActiveHold(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        Paise.of(rs.getLong("amount_paise")),
                        Paise.of(rs.getLong("committed_paise"))))
                .optional();
    }

    private void update(UUID id, Paise amount, Paise committed) {
        jdbc.sql("update billing.reservations set amount_paise = :amount, committed_paise = :committed where id = :id")
                .param("amount", amount.value())
                .param("committed", committed.value())
                .param("id", id)
                .update();
    }

    private void change(
            UUID reservationId, UUID sessionId, String reason, Paise before, Paise after, @Nullable UUID txnId) {
        jdbc.sql("""
                        insert into billing.reservation_changes
                            (id, reservation_id, session_id, reason, before_paise, after_paise, txn_id, created_at)
                        values (:id, :reservationId, :sessionId, :reason, :before, :after, :txnId, :now)
                        """)
                .param("id", ids.newId())
                .param("reservationId", reservationId)
                .param("sessionId", sessionId)
                .param("reason", reason)
                .param("before", before.value())
                .param("after", after.value())
                .param("txnId", txnId)
                .param("now", Timestamp.from(now()))
                .update();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
