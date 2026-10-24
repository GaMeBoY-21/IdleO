package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.HostPayouts;
import com.idleo.controlplane.hosts.HostOwners;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's side of paying hosts (ADR 0040). An owner's machines each have their {@code host_payable}; a session's
 * net earnings on a machine are pending until {@code legal.payouts.dispute-window} after the session's last charge, and
 * while a report holds them; the rest of a machine's balance is available. Payouts, recoveries and clawbacks keep
 * every machine's balance at zero or above (verify-ledger.sh's check).
 *
 * <p>The postings, each named by its cause:
 *
 * <ul>
 *   <li>{@code payout}: each machine's {@code host_payable} debited, {@code payouts_in_flight} credited the net, {@code
 *       tds_payable} the tax and {@code platform_revenue} the owner's part of the fee;
 *   <li>{@code payout_paid}: {@code payouts_in_flight} to {@code payouts_paid};
 *   <li>{@code payout_returned} and {@code payout_reversed}: the first posting undone, from in flight or from paid;
 *   <li>{@code earnings_recovered}: {@code host_payable} debited, {@code host_receivable} credited;
 *   <li>{@code earnings_forfeited}: {@code host_payable} debited, {@code earnings_forfeited} credited.
 * </ul>
 */
@Service
class HostPayoutService implements HostPayouts {

    static final String PAYOUT_REF = "payout";
    static final String PAID_REF = "payout_paid";
    static final String RETURNED_REF = "payout_returned";
    static final String REVERSED_REF = "payout_reversed";
    static final String RECOVERED_REF = "earnings_recovered";
    static final String FORFEITED_REF = "earnings_forfeited";

    /** What a machine earns, gives back for lost time, and refunds to renters: its earnings, by session. */
    static final List<String> EARNING_REFS =
            List.of(SessionChargeService.CHARGE_REF, SessionChargeService.REFUND_REF, SessionRefundService.REFUND_REF);

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final HostOwners owners;
    private final PayoutHoldService holds;
    private final Clawbacks clawbacks;
    private final LegalSettings legal;
    private final Clock clock;

    HostPayoutService(
            JdbcClient jdbc,
            Ledger ledger,
            HostOwners owners,
            PayoutHoldService holds,
            Clawbacks clawbacks,
            LegalSettings legal,
            Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.owners = owners;
        this.holds = holds;
        this.clawbacks = clawbacks;
        this.legal = legal;
        this.clock = clock;
    }

    /** A session's net on one machine, and its last charge. */
    record SessionNet(UUID hostId, UUID sessionId, long net, Instant lastAt) {}

    /** One machine's balance, and the part of it not available yet. */
    record Machine(UUID hostId, long balance, long pending) {

        long available() {
            return Math.max(0, balance - pending);
        }
    }

    @Override
    public Position position(UUID ownerId) {
        var hosts = hosts(ownerId);
        var now = clock.instant();
        var pending = pendingSessions(hosts, now);
        var machines = machines(hosts, pending);
        long unpaid = machines.stream().mapToLong(Machine::balance).sum();
        long available = machines.stream().mapToLong(Machine::available).sum();
        return new Position(
                unpaid,
                Math.max(0, unpaid - available),
                available,
                ownerBalance(Account.HOST_RECEIVABLE, ownerId),
                ownerBalance(Account.PAYOUTS_IN_FLIGHT, ownerId),
                ownerBalance(Account.PAYOUTS_PAID, ownerId),
                pending);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Started> start(UUID payoutId, UUID ownerId, Paise minimum, Function<Paise, Deductions> deductions) {
        clawbacks.lockOwner(ownerId);
        var hosts = hosts(ownerId);
        var now = clock.instant();
        var pending = pendingSessions(hosts, now);
        var available = new LinkedHashMap<UUID, Long>();
        for (var machine : machines(hosts, pending)) {
            if (machine.available() > 0) {
                available.put(machine.hostId(), machine.available());
            }
        }
        var recovered = recover(payoutId, ownerId, available);
        long payable = available.values().stream().mapToLong(Long::longValue).sum();
        if (payable < minimum.value() || payable <= 0) {
            return Optional.empty();
        }
        var gross = Paise.of(payable);
        var deducted = deductions.apply(gross);
        var net = gross.minus(deducted.tds()).minus(deducted.fee());
        if (net.isNegative()
                || !net.isPositive()
                || deducted.tds().isNegative()
                || deducted.fee().isNegative()) {
            throw new IllegalArgumentException("a payout's deductions leave nothing to pay");
        }
        var entries = new ArrayList<LedgerEntry>();
        available.forEach(
                (hostId, amount) -> entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, hostId, Paise.of(amount))));
        entries.add(LedgerEntry.credit(Account.PAYOUTS_IN_FLIGHT, ownerId, net));
        if (deducted.tds().isPositive()) {
            entries.add(LedgerEntry.credit(Account.TDS_PAYABLE, null, deducted.tds()));
        }
        if (deducted.fee().isPositive()) {
            entries.add(LedgerEntry.credit(Account.PLATFORM_REVENUE, null, deducted.fee()));
        }
        ledger.post(new LedgerTransaction(txn(PAYOUT_REF, payoutId), PAYOUT_REF, payoutId, entries));
        recordSessions(payoutId, available.keySet(), pending);
        var oldest = jdbc.sql("""
                        select min(e.created_at) from billing.payout_sessions ps
                        join billing.ledger_entries e
                          on e.ref_id = ps.session_id and e.owner_id = ps.host_id and e.account = 'host_payable'
                        where ps.payout_id = :payoutId
                        """)
                .param("payoutId", payoutId)
                .query((rs, n) -> rs.getTimestamp(1))
                .single();
        return Optional.of(new Started(
                payoutId,
                recovered,
                gross,
                deducted.tds(),
                deducted.fee(),
                oldest == null ? null : oldest.toInstant()));
    }

    /**
     * What the owner owes, taken from what's available, machine by machine: {@code available} is left holding what's
     * still there to pay.
     */
    private Paise recover(UUID payoutId, UUID ownerId, Map<UUID, Long> available) {
        long owed = ownerBalance(Account.HOST_RECEIVABLE, ownerId);
        if (owed <= 0) {
            return Paise.ZERO;
        }
        var entries = new ArrayList<LedgerEntry>();
        long recovered = 0;
        for (var machine : available.entrySet()) {
            long take = Math.min(owed - recovered, machine.getValue());
            if (take > 0) {
                entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, machine.getKey(), Paise.of(take)));
                machine.setValue(machine.getValue() - take);
                recovered += take;
            }
        }
        available.values().removeIf(amount -> amount <= 0);
        if (recovered == 0) {
            return Paise.ZERO;
        }
        entries.add(LedgerEntry.credit(Account.HOST_RECEIVABLE, ownerId, Paise.of(recovered)));
        ledger.post(new LedgerTransaction(txn(RECOVERED_REF, payoutId), RECOVERED_REF, payoutId, entries));
        clawbacks.recovered(ownerId, Paise.of(recovered));
        return Paise.of(recovered);
    }

    /** The sessions this payout paid each machine for, so a later refund of one is known as a clawback. */
    private void recordSessions(UUID payoutId, java.util.Set<UUID> hostIds, List<PendingSession> pending) {
        if (hostIds.isEmpty()) {
            return;
        }
        var notYet = new java.util.HashSet<List<UUID>>();
        pending.forEach(session -> notYet.add(List.of(session.hostId(), session.sessionId())));
        var unpaid = jdbc.sql("""
                        with nets as (
                            select owner_id as host_id, ref_id as session_id,
                                   sum(case direction when 'credit' then amount_paise else -amount_paise end) as net
                            from billing.ledger_entries
                            where account = 'host_payable' and owner_id in (:hosts) and ref_type in (:refs)
                            group by owner_id, ref_id),
                        covered as (
                            select ps.host_id, ps.session_id, sum(ps.amount_paise) as paid
                            from billing.payout_sessions ps
                            where ps.host_id in (:hosts)
                              and not exists (select 1 from billing.ledger_entries r
                                              where r.ref_id = ps.payout_id and r.ref_type in (:back))
                            group by ps.host_id, ps.session_id)
                        select n.host_id, n.session_id, n.net - coalesce(c.paid, 0) as unpaid
                        from nets n left join covered c on c.host_id = n.host_id and c.session_id = n.session_id
                        where n.net - coalesce(c.paid, 0) > 0
                        """)
                .param("hosts", hostIds)
                .param("refs", EARNING_REFS)
                .param("back", List.of(RETURNED_REF, REVERSED_REF))
                .query((rs, n) -> new SessionNet(
                        rs.getObject("host_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getLong("unpaid"),
                        Instant.EPOCH))
                .list();
        for (var session : unpaid) {
            if (notYet.contains(List.of(session.hostId(), session.sessionId()))) {
                continue;
            }
            jdbc.sql("""
                            insert into billing.payout_sessions (payout_id, host_id, session_id, amount_paise)
                            values (:payoutId, :hostId, :sessionId, :amount)
                            on conflict do nothing
                            """)
                    .param("payoutId", payoutId)
                    .param("hostId", session.hostId())
                    .param("sessionId", session.sessionId())
                    .param("amount", session.net())
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void paid(UUID payoutId) {
        var started = started(payoutId);
        if (posted(RETURNED_REF, payoutId) || posted(REVERSED_REF, payoutId)) {
            throw new IllegalStateException("payout " + payoutId + " came back already; it can't be paid");
        }
        ledger.post(new LedgerTransaction(
                txn(PAID_REF, payoutId),
                PAID_REF,
                payoutId,
                List.of(
                        LedgerEntry.debit(Account.PAYOUTS_IN_FLIGHT, started.ownerId(), started.net()),
                        LedgerEntry.credit(Account.PAYOUTS_PAID, started.ownerId(), started.net()))));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void returned(UUID payoutId) {
        var started = started(payoutId);
        if (posted(RETURNED_REF, payoutId) || posted(REVERSED_REF, payoutId)) {
            return;
        }
        clawbacks.lockOwner(started.ownerId());
        boolean wasPaid = posted(PAID_REF, payoutId);
        var ref = wasPaid ? REVERSED_REF : RETURNED_REF;
        var entries = new ArrayList<LedgerEntry>();
        entries.add(LedgerEntry.debit(
                wasPaid ? Account.PAYOUTS_PAID : Account.PAYOUTS_IN_FLIGHT, started.ownerId(), started.net()));
        if (started.tds().isPositive()) {
            entries.add(LedgerEntry.debit(Account.TDS_PAYABLE, null, started.tds()));
        }
        if (started.fee().isPositive()) {
            entries.add(LedgerEntry.debit(Account.PLATFORM_REVENUE, null, started.fee()));
        }
        started.machines()
                .forEach((hostId, amount) -> entries.add(LedgerEntry.credit(Account.HOST_PAYABLE, hostId, amount)));
        ledger.post(new LedgerTransaction(txn(ref, payoutId), ref, payoutId, entries));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Paise forfeit(UUID forfeitId, UUID ownerId) {
        clawbacks.lockOwner(ownerId);
        var entries = new ArrayList<LedgerEntry>();
        long total = 0;
        for (var machine : machines(hosts(ownerId), List.of())) {
            if (machine.balance() > 0) {
                entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, machine.hostId(), Paise.of(machine.balance())));
                total += machine.balance();
            }
        }
        if (total == 0) {
            return Paise.ZERO;
        }
        entries.add(LedgerEntry.credit(Account.EARNINGS_FORFEITED, null, Paise.of(total)));
        ledger.post(new LedgerTransaction(txn(FORFEITED_REF, forfeitId), FORFEITED_REF, forfeitId, entries));
        return Paise.of(total);
    }

    /** A started payout, read back from its posting. */
    private record StartedPosting(UUID ownerId, Paise net, Paise tds, Paise fee, Map<UUID, Paise> machines) {}

    private StartedPosting started(UUID payoutId) {
        UUID ownerId = null;
        var net = Paise.ZERO;
        var tds = Paise.ZERO;
        var fee = Paise.ZERO;
        var machines = new LinkedHashMap<UUID, Paise>();
        record Row(Account account, @Nullable UUID ownerId, long amount) {}
        var rows = jdbc.sql("""
                        select account, owner_id, amount_paise from billing.ledger_entries
                        where txn_id = :txnId and ref_type = :ref order by account, owner_id
                        """)
                .param("txnId", txn(PAYOUT_REF, payoutId))
                .param("ref", PAYOUT_REF)
                .query((rs, n) -> new Row(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise")))
                .list();
        for (var row : rows) {
            switch (row.account()) {
                case PAYOUTS_IN_FLIGHT -> {
                    ownerId = row.ownerId();
                    net = Paise.of(row.amount());
                }
                case TDS_PAYABLE -> tds = Paise.of(row.amount());
                case PLATFORM_REVENUE -> fee = Paise.of(row.amount());
                case HOST_PAYABLE -> machines.put(row.ownerId(), Paise.of(row.amount()));
                default -> throw new IllegalStateException("a payout never touches " + row.account());
            }
        }
        if (ownerId == null) {
            throw new IllegalStateException("payout " + payoutId + " was never started");
        }
        return new StartedPosting(ownerId, net, tds, fee, machines);
    }

    private boolean posted(String ref, UUID payoutId) {
        return jdbc.sql("select exists (select 1 from billing.ledger_entries where txn_id = :txnId)")
                .param("txnId", txn(ref, payoutId))
                .query(Boolean.class)
                .single();
    }

    private List<UUID> hosts(UUID ownerId) {
        return owners.ownedBy(ownerId).stream().map(HostOwners.OwnedHost::id).toList();
    }

    /**
     * Each session's earnings not available yet: charged within the dispute window, or held by an open report. Only
     * sessions with a net above zero; the soonest available first, held ones last.
     */
    private List<PendingSession> pendingSessions(List<UUID> hosts, Instant now) {
        if (hosts.isEmpty()) {
            return List.of();
        }
        var window = legal.payouts().disputeWindow();
        var cutoff = now.minus(window);
        var recent = jdbc.sql("""
                        select owner_id, ref_id,
                               sum(case direction when 'credit' then amount_paise else -amount_paise end) as net,
                               max(created_at) as last_at
                        from billing.ledger_entries
                        where account = 'host_payable' and owner_id in (:hosts) and ref_type in (:refs)
                          and ref_id in (select ref_id from billing.ledger_entries
                                         where account = 'host_payable' and owner_id in (:hosts)
                                           and ref_type in (:refs) and created_at > :cutoff)
                        group by owner_id, ref_id
                        """)
                .param("hosts", hosts)
                .param("refs", EARNING_REFS)
                .param("cutoff", Timestamp.from(cutoff))
                .query(HostPayoutService::sessionNet)
                .list();
        var byKey = new LinkedHashMap<List<UUID>, SessionNet>();
        recent.forEach(net -> byKey.put(List.of(net.hostId(), net.sessionId()), net));
        // Held sessions: whatever their age, their earnings wait for the report.
        var held = holds.heldAmong(hosts);
        var heldNets = held.isEmpty()
                ? List.<SessionNet>of()
                : jdbc.sql("""
                                select owner_id, ref_id,
                                       sum(case direction when 'credit' then amount_paise else -amount_paise end) as net,
                                       max(created_at) as last_at
                                from billing.ledger_entries
                                where account = 'host_payable' and owner_id in (:hosts) and ref_type in (:refs)
                                  and ref_id in (:held)
                                group by owner_id, ref_id
                                """)
                        .param("hosts", hosts)
                        .param("refs", EARNING_REFS)
                        .param("held", held)
                        .query(HostPayoutService::sessionNet)
                        .list();
        heldNets.forEach(net -> byKey.put(List.of(net.hostId(), net.sessionId()), net));
        var pending = new ArrayList<PendingSession>();
        for (var net : byKey.values()) {
            if (net.net() <= 0) {
                continue;
            }
            boolean isHeld = held.contains(net.sessionId());
            pending.add(new PendingSession(
                    net.hostId(),
                    net.sessionId(),
                    net.net(),
                    isHeld ? null : net.lastAt().plus(window)));
        }
        pending.sort(Comparator.comparing(PendingSession::availableAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PendingSession::sessionId));
        return pending;
    }

    private static SessionNet sessionNet(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SessionNet(
                rs.getObject("owner_id", UUID.class),
                rs.getObject("ref_id", UUID.class),
                rs.getLong("net"),
                rs.getTimestamp("last_at").toInstant());
    }

    /** Every machine with a balance or something pending, in a fixed order. */
    private List<Machine> machines(List<UUID> hosts, List<PendingSession> pending) {
        if (hosts.isEmpty()) {
            return List.of();
        }
        var balances = new HashMap<UUID, Long>();
        jdbc.sql("""
                        select owner_id,
                               sum(case direction when 'credit' then amount_paise else -amount_paise end) as balance
                        from billing.ledger_entries
                        where account = 'host_payable' and owner_id in (:hosts)
                        group by owner_id
                        """).param("hosts", hosts).query(rs -> {
            balances.put(rs.getObject("owner_id", UUID.class), rs.getLong("balance"));
        });
        var pendingByHost = new HashMap<UUID, Long>();
        pending.forEach(session -> pendingByHost.merge(session.hostId(), session.amountPaise(), Long::sum));
        return hosts.stream()
                .sorted()
                .filter(hostId -> balances.containsKey(hostId))
                .map(hostId -> new Machine(
                        hostId,
                        balances.get(hostId),
                        Math.min(Math.max(0, balances.get(hostId)), pendingByHost.getOrDefault(hostId, 0L))))
                .toList();
    }

    private long ownerBalance(Account account, UUID ownerId) {
        long net = jdbc.sql("""
                        select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries where account = :account and owner_id = :owner
                        """)
                .param("account", account.dbValue)
                .param("owner", ownerId)
                .query(Long.class)
                .single();
        return account.raisedByCredits ? net : -net;
    }

    static UUID txn(String ref, UUID cause) {
        return UUID.nameUUIDFromBytes((ref + ":" + cause).getBytes(StandardCharsets.UTF_8));
    }
}
