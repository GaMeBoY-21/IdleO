package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.SessionCharges;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles and refunds session charges (ADR 0017), always in this order under the renter's wallet lock: the hold
 * shrinks by the charge, the ledger transaction posts, and the hold is topped back up. Shrinking first keeps the
 * wallet's reservation within its balance at every step, which the database insists on.
 */
@Service
class SessionChargeService implements SessionCharges {

    static final String CHARGE_REF = "session";
    static final String REFUND_REF = "session_refund";

    private final Ledger ledger;
    private final CreditReservationService reservations;
    private final int commissionPercent;

    SessionChargeService(
            Ledger ledger,
            CreditReservationService reservations,
            @Value("${billing.commission-percent}") int commissionPercent) {
        if (commissionPercent < 0 || commissionPercent > 100) {
            throw new IllegalStateException("billing.commission-percent must be 0 to 100");
        }
        this.ledger = ledger;
        this.reservations = reservations;
        this.commissionPercent = commissionPercent;
    }

    @Override
    public int commissionPercent() {
        return commissionPercent;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Charged settle(Charge charge) {
        // One part per host: two attempts on one host are one credit.
        var byHost = new LinkedHashMap<UUID, Paise>();
        for (var part : charge.parts()) {
            byHost.merge(part.hostId(), part.amount(), Paise::plus);
        }
        var amount = Paise.ZERO;
        var hostShare = Paise.ZERO;
        var entries = new ArrayList<LedgerEntry>();
        var hosts = new ArrayList<HostShare>();
        for (var part : byHost.entrySet()) {
            var split = SessionCharges.split(part.getValue(), commissionPercent);
            amount = amount.plus(part.getValue());
            hostShare = hostShare.plus(split.hostShare());
            hosts.add(new HostShare(part.getKey(), part.getValue(), split.hostShare()));
            if (split.hostShare().isPositive()) {
                entries.add(LedgerEntry.credit(Account.HOST_PAYABLE, part.getKey(), split.hostShare()));
            }
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a settlement charges more than zero");
        }
        var commission = amount.minus(hostShare);
        entries.addFirst(LedgerEntry.debit(Account.RENTER_WALLET, charge.renterId(), amount));
        if (commission.isPositive()) {
            entries.add(LedgerEntry.credit(Account.PLATFORM_REVENUE, null, commission));
        }
        ledger.lockWallet(charge.renterId());
        if (reservations.shrinkForSettlement(charge.sessionId(), amount, charge.txnId())) {
            ledger.post(new LedgerTransaction(charge.txnId(), CHARGE_REF, charge.sessionId(), entries));
            reservations.topUp(charge.sessionId(), charge.holdTarget());
        }
        return new Charged(amount, hostShare, commission, List.copyOf(hosts));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(UUID sessionId, UUID renterId, UUID txnId, List<RefundPart> parts) {
        var hosts = new LinkedHashMap<UUID, Paise>();
        var platform = Paise.ZERO;
        for (var part : parts) {
            hosts.merge(part.hostId(), part.host(), Paise::plus);
            platform = platform.plus(part.platform());
        }
        var entries = new ArrayList<LedgerEntry>();
        var total = platform;
        for (var host : hosts.entrySet()) {
            total = total.plus(host.getValue());
            if (host.getValue().isPositive()) {
                entries.add(LedgerEntry.debit(Account.HOST_PAYABLE, host.getKey(), host.getValue()));
            }
        }
        if (!total.isPositive()) {
            return;
        }
        if (platform.isPositive()) {
            entries.add(LedgerEntry.debit(Account.PLATFORM_REVENUE, null, platform));
        }
        entries.addFirst(LedgerEntry.credit(Account.RENTER_WALLET, renterId, total));
        ledger.post(new LedgerTransaction(txnId, REFUND_REF, sessionId, entries));
    }
}
