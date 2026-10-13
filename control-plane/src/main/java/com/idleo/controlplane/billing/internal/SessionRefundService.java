package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.SessionRefunds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds for resolved reports (ADR 0028), from the session's own ledger entries: its settlements ({@code session}),
 * lost-time refunds ({@code session_refund}) and earlier report refunds ({@code dispute_refund}), all of which name
 * the session as their reference.
 */
@Service
class SessionRefundService implements SessionRefunds {

    static final String REFUND_REF = "dispute_refund";

    private static final List<String> SESSION_REFS =
            List.of(SessionChargeService.CHARGE_REF, SessionChargeService.REFUND_REF, REFUND_REF);

    private final Ledger ledger;
    private final JdbcClient jdbc;

    SessionRefundService(Ledger ledger, JdbcClient jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    /** One account's net for the session: credits less debits. */
    private record Net(Account account, @Nullable UUID ownerId, long net) {}

    @Override
    public Paid paid(UUID sessionId) {
        return paid(nets(sessionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refund(UUID sessionId, UUID renterId, UUID txnId, Paise amount) {
        var posted = posted(txnId);
        if (posted != null) {
            return posted;
        }
        ledger.lockWallet(renterId);
        var nets = nets(sessionId);
        boolean renters = nets.stream()
                .filter(net -> net.account() == Account.RENTER_WALLET)
                .allMatch(net -> renterId.equals(net.ownerId()));
        if (!renters) {
            throw new IllegalArgumentException("session " + sessionId + " wasn't charged to this renter");
        }
        var split = SessionRefunds.split(paid(nets), amount);
        var entries = new ArrayList<LedgerEntry>();
        entries.add(LedgerEntry.credit(Account.RENTER_WALLET, renterId, amount));
        if (split.fromCommission().isPositive()) {
            entries.add(LedgerEntry.debit(Account.PLATFORM_REVENUE, null, split.fromCommission()));
        }
        for (var host : split.fromHosts()) {
            entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, host.hostId(), host.amount()));
        }
        ledger.post(new LedgerTransaction(txnId, REFUND_REF, sessionId, entries));
        return new Refund(txnId, split);
    }

    @Override
    public Paise refunded(UUID sessionId) {
        return Paise.of(jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) from billing.ledger_entries
                        where ref_id = :sessionId and ref_type = :refType and account = :account
                        """)
                .param("sessionId", sessionId)
                .param("refType", REFUND_REF)
                .param("account", Account.RENTER_WALLET.dbValue)
                .query(Long.class)
                .single());
    }

    private List<Net> nets(UUID sessionId) {
        return jdbc.sql("""
                        select account, owner_id,
                               sum(case direction when 'credit' then amount_paise else -amount_paise end) as net
                        from billing.ledger_entries
                        where ref_id = :sessionId and ref_type in (:refTypes)
                        group by account, owner_id
                        """)
                .param("sessionId", sessionId)
                .param("refTypes", SESSION_REFS)
                .query((rs, n) -> new Net(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("net")))
                .list();
    }

    private static Paid paid(List<Net> nets) {
        long renterPaid = 0;
        long commission = 0;
        var hosts = new ArrayList<HostPart>();
        for (var net : nets) {
            switch (net.account()) {
                case RENTER_WALLET -> renterPaid -= net.net();
                case PLATFORM_REVENUE -> commission += net.net();
                case HOST_PAYABLE -> hosts.add(new HostPart(net.ownerId(), Paise.of(net.net())));
                default -> throw new IllegalStateException("a session's entries never touch " + net.account());
            }
        }
        hosts.sort(Comparator.comparing((HostPart host) -> host.amount().value())
                .reversed()
                .thenComparing(HostPart::hostId));
        return new Paid(Paise.of(renterPaid), Paise.of(commission), List.copyOf(hosts));
    }

    /** The refund already posted under {@code txnId}, read back from its entries; null if there isn't one. */
    private @Nullable Refund posted(UUID txnId) {
        var entries = jdbc.sql("""
                        select account, owner_id, amount_paise from billing.ledger_entries
                        where txn_id = :txnId and ref_type = :refType
                        """)
                .param("txnId", txnId)
                .param("refType", REFUND_REF)
                .query((rs, n) -> new Net(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise")))
                .list();
        if (entries.isEmpty()) {
            return null;
        }
        var amount = Paise.ZERO;
        var fromCommission = Paise.ZERO;
        var fromHosts = new ArrayList<HostPart>();
        for (var entry : entries) {
            switch (entry.account()) {
                case RENTER_WALLET -> amount = Paise.of(entry.net());
                case PLATFORM_REVENUE -> fromCommission = Paise.of(entry.net());
                case HOST_PAYABLE -> fromHosts.add(new HostPart(entry.ownerId(), Paise.of(entry.net())));
                default -> throw new IllegalStateException("a refund never touches " + entry.account());
            }
        }
        fromHosts.sort(Comparator.comparing(HostPart::hostId));
        return new Refund(txnId, new Split(amount, fromCommission, List.copyOf(fromHosts)));
    }
}
