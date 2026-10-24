package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.EarningsClawedBack;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Earnings refunded to a renter after they were paid out (ADR 0040). A report's refund debits the machine's {@code
 * host_payable} as always; when the session was paid out, that part is a clawback:
 *
 * <ul>
 *   <li>the owner's balance covers what it can, the pending and the available alike, moved between their machines so
 *       none is left below zero;
 *   <li>the rest becomes a {@code host_receivable}, recovered from what they earn next before any payout ({@code
 *       HostPayoutService.start}), and never asked of their UPI ID;
 *   <li>an admin may waive what's still owed, with a reason, audited: IdleO absorbs it into {@code chargeback_losses}.
 * </ul>
 *
 * The owner is told once ({@link EarningsClawedBack}).
 */
@Component
class Clawbacks {

    static final String CLAWBACK_REF = "clawback";
    static final String WAIVED_REF = "clawback_waived";

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final HostOwners owners;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final BillingMetrics metrics;

    Clawbacks(
            JdbcClient jdbc,
            Ledger ledger,
            HostOwners owners,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            AuditTrail audit,
            BillingMetrics metrics) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.owners = owners;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.metrics = metrics;
    }

    record Clawback(
            UUID id,
            UUID ownerId,
            UUID hostId,
            UUID sessionId,
            String cause,
            UUID causeTxnId,
            long amountPaise,
            long fromBalancePaise,
            long owedPaise,
            long recoveredPaise,
            long waivedPaise,
            String state,
            Instant createdAt,
            @Nullable Instant settledAt,
            @Nullable String waivedBy,
            @Nullable String waiveReason) {

        long outstandingPaise() {
            return owedPaise - recoveredPaise - waivedPaise;
        }
    }

    /** Takes the owner's lock for the rest of the transaction: payouts, refunds and recoveries take turns. */
    void lockOwner(UUID ownerId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "host-owner:" + ownerId)
                .query((rs, n) -> null)
                .list();
    }

    /**
     * A report's refund ({@code txnId}) just took {@code amount} from the machine for the session: if the session was
     * paid out, it's a clawback, covered by the owner's balance as far as it goes and owed for the rest. Runs in the
     * refund's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void afterRefund(UUID txnId, UUID sessionId, UUID hostId, Paise amount) {
        var ownerId = owners.ownerOf(hostId).orElse(null);
        if (ownerId == null) {
            return;
        }
        lockOwner(ownerId);
        long paidOut = paidOut(hostId, sessionId);
        long clawedBefore = jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) from billing.clawbacks
                        where host_id = :hostId and session_id = :sessionId and cause_txn_id <> :txnId
                        """)
                .param("hostId", hostId)
                .param("sessionId", sessionId)
                .param("txnId", txnId)
                .query(Long.class)
                .single();
        long clawed = Math.max(0, Math.min(amount.value(), paidOut - clawedBefore));
        long owed = cover(txnId, ownerId, hostId);
        clawed = Math.max(clawed, owed);
        if (clawed == 0) {
            return;
        }
        var id = ids.newId();
        var now = now();
        int inserted = jdbc.sql("""
                        insert into billing.clawbacks
                            (id, owner_id, host_id, session_id, cause, cause_txn_id, amount_paise, from_balance_paise,
                             owed_paise, state, created_at, settled_at)
                        values (:id, :ownerId, :hostId, :sessionId, 'report_refund', :txnId, :amount, :fromBalance,
                                :owed, :state, :now, :settledAt)
                        on conflict (cause_txn_id, host_id) do nothing
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("hostId", hostId)
                .param("sessionId", sessionId)
                .param("txnId", txnId)
                .param("amount", clawed)
                .param("fromBalance", clawed - owed)
                .param("owed", owed)
                .param("state", owed > 0 ? "owed" : "recovered")
                .param("now", Timestamp.from(now))
                .param("settledAt", owed > 0 ? null : Timestamp.from(now))
                .update();
        if (inserted == 1) {
            events.publishEvent(new EarningsClawedBack(id, ownerId, sessionId, clawed, clawed - owed, owed, now));
            metrics.clawedBack(clawed, owed);
        }
    }

    /** What payouts that didn't come back paid the machine for the session. */
    private long paidOut(UUID hostId, UUID sessionId) {
        return jdbc.sql("""
                        select coalesce(sum(ps.amount_paise), 0) from billing.payout_sessions ps
                        where ps.host_id = :hostId and ps.session_id = :sessionId
                          and not exists (select 1 from billing.ledger_entries r
                                          where r.ref_id = ps.payout_id and r.ref_type in (:back))
                        """)
                .param("hostId", hostId)
                .param("sessionId", sessionId)
                .param("back", List.of(HostPayoutService.RETURNED_REF, HostPayoutService.REVERSED_REF))
                .query(Long.class)
                .single();
    }

    /**
     * Brings the machine back to zero when the refund took it below: from the owner's other machines first, and for the
     * rest a receivable. Returns what's owed.
     */
    private long cover(UUID txnId, UUID ownerId, UUID hostId) {
        var balances = new TreeMap<UUID, Long>();
        var hosts =
                owners.ownedBy(ownerId).stream().map(HostOwners.OwnedHost::id).toList();
        jdbc.sql("""
                        select owner_id,
                               sum(case direction when 'credit' then amount_paise else -amount_paise end) as balance
                        from billing.ledger_entries where account = 'host_payable' and owner_id in (:hosts)
                        group by owner_id
                        """).param("hosts", hosts).query(rs -> {
            balances.put(rs.getObject("owner_id", UUID.class), rs.getLong("balance"));
        });
        long shortfall = -balances.getOrDefault(hostId, 0L);
        if (shortfall <= 0) {
            return 0;
        }
        var entries = new ArrayList<LedgerEntry>();
        entries.add(LedgerEntry.credit(Account.HOST_PAYABLE, hostId, Paise.of(shortfall)));
        long left = shortfall;
        for (Map.Entry<UUID, Long> other : balances.entrySet()) {
            if (left == 0) {
                break;
            }
            if (other.getKey().equals(hostId) || other.getValue() <= 0) {
                continue;
            }
            long take = Math.min(left, other.getValue());
            entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, other.getKey(), Paise.of(take)));
            left -= take;
        }
        if (left > 0) {
            entries.add(LedgerEntry.debit(Account.HOST_RECEIVABLE, ownerId, Paise.of(left)));
        }
        ledger.post(new LedgerTransaction(
                HostPayoutService.txn(
                        CLAWBACK_REF,
                        UUID.nameUUIDFromBytes(
                                (txnId + ":" + hostId).getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                CLAWBACK_REF,
                txnId,
                entries));
        return left;
    }

    /** {@code amount} of what the owner owed was recovered from their earnings: the oldest clawbacks first. */
    @Transactional(propagation = Propagation.MANDATORY)
    void recovered(UUID ownerId, Paise amount) {
        long left = amount.value();
        for (var clawback : owed(ownerId)) {
            if (left == 0) {
                break;
            }
            long take = Math.min(left, clawback.outstandingPaise());
            left -= take;
            boolean done = take == clawback.outstandingPaise();
            jdbc.sql("""
                            update billing.clawbacks
                            set recovered_paise = recovered_paise + :take,
                                state = case when :done then (case when waived_paise > 0 then 'waived' else 'recovered'
                                                              end) else state end,
                                settled_at = case when :done then :now else settled_at end
                            where id = :id
                            """)
                    .param("take", take)
                    .param("done", done)
                    .param("now", Timestamp.from(now()))
                    .param("id", clawback.id())
                    .update();
        }
    }

    /**
     * Waives what's still owed on a clawback (ADR 0040): IdleO absorbs it into {@code chargeback_losses}, one balanced
     * transaction and one audit row, both in this transaction.
     *
     * @throws IllegalArgumentException when nothing is owed on it any more
     */
    @Transactional
    Clawback waive(UUID clawbackId, String reason, AuditTrail.Actor actor) {
        var before = find(clawbackId).orElseThrow(() -> new IllegalArgumentException("no clawback " + clawbackId));
        lockOwner(before.ownerId());
        var clawback = find(clawbackId).orElseThrow();
        long outstanding = clawback.outstandingPaise();
        if (outstanding <= 0) {
            throw new IllegalArgumentException("Nothing is owed on this clawback any more.");
        }
        ledger.post(new LedgerTransaction(
                HostPayoutService.txn(WAIVED_REF, clawbackId),
                WAIVED_REF,
                clawbackId,
                List.of(
                        LedgerEntry.debit(Account.CHARGEBACK_LOSSES, null, Paise.of(outstanding)),
                        LedgerEntry.credit(Account.HOST_RECEIVABLE, clawback.ownerId(), Paise.of(outstanding)))));
        jdbc.sql("""
                        update billing.clawbacks
                        set waived_paise = waived_paise + :amount, state = 'waived', settled_at = :now,
                            waived_by = :by, waive_reason = :reason
                        where id = :id
                        """)
                .param("amount", outstanding)
                .param("now", Timestamp.from(now()))
                .param("by", actor.label())
                .param("reason", reason)
                .param("id", clawbackId)
                .update();
        audit.record(new AuditTrail.Entry(
                actor,
                "clawback.waive",
                "clawback",
                clawbackId.toString(),
                reason,
                Map.of("outstandingPaise", outstanding),
                Map.of("waivedPaise", outstanding)));
        metrics.clawbackWaived(outstanding);
        return find(clawbackId).orElseThrow();
    }

    Optional<Clawback> find(UUID id) {
        return jdbc.sql("select * from billing.clawbacks where id = :id")
                .param("id", id)
                .query(Clawbacks::clawback)
                .optional();
    }

    /** What the owner still owes, oldest first. */
    List<Clawback> owed(UUID ownerId) {
        return jdbc.sql("""
                        select * from billing.clawbacks where owner_id = :ownerId and state = 'owed'
                        order by created_at, id
                        """)
                .param("ownerId", ownerId)
                .query(Clawbacks::clawback)
                .list();
    }

    /** The owner's clawbacks, newest first. */
    List<Clawback> of(UUID ownerId, int limit) {
        return jdbc.sql("select * from billing.clawbacks where owner_id = :ownerId order by created_at desc, id desc"
                        + " limit " + limit)
                .param("ownerId", ownerId)
                .query(Clawbacks::clawback)
                .list();
    }

    /** The clawbacks on these refund transactions, by transaction and machine. */
    Map<List<UUID>, Clawback> byCause(java.util.Collection<UUID> txnIds) {
        var found = new java.util.HashMap<List<UUID>, Clawback>();
        if (txnIds.isEmpty()) {
            return found;
        }
        jdbc.sql("select * from billing.clawbacks where cause_txn_id in (:txnIds)")
                .param("txnIds", txnIds)
                .query(Clawbacks::clawback)
                .list()
                .forEach(clawback -> found.put(List.of(clawback.causeTxnId(), clawback.hostId()), clawback));
        return found;
    }

    /** For the admin console: {@code state} owed, recovered, waived or all, newest first, a page after {@code after}. */
    List<Clawback> list(String state, @Nullable UUID after, int limit) {
        return jdbc.sql("select * from billing.clawbacks where (:state = 'all' or state = :state)"
                        + " and (cast(:after as uuid) is null or id < :after) order by id desc limit " + limit)
                .param("state", state)
                .param("after", after)
                .query(Clawbacks::clawback)
                .list();
    }

    private static Clawback clawback(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var settled = rs.getTimestamp("settled_at");
        return new Clawback(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("cause"),
                rs.getObject("cause_txn_id", UUID.class),
                rs.getLong("amount_paise"),
                rs.getLong("from_balance_paise"),
                rs.getLong("owed_paise"),
                rs.getLong("recovered_paise"),
                rs.getLong("waived_paise"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                settled == null ? null : settled.toInstant(),
                rs.getString("waived_by"),
                rs.getString("waive_reason"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
