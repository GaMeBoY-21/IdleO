package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.SessionCharges;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p>A charge draws from the renter's credit lots in the spending order (ADR 0039); lost time given back returns to
 * the lots it was drawn from, bought credit first, and to a new free lot for any part whose free lot has expired.
 */
@Service
class SessionChargeService implements SessionCharges {

    static final String CHARGE_REF = "session";
    static final String REFUND_REF = "session_refund";

    private final Ledger ledger;
    private final CreditReservationService reservations;
    private final int commissionPercent;
    private final LegalSettings legal;
    private final Clock clock;

    SessionChargeService(
            Ledger ledger,
            CreditReservationService reservations,
            @Value("${billing.commission-percent}") int commissionPercent,
            LegalSettings legal,
            Clock clock) {
        this.legal = legal;
        this.clock = clock;
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
        if (commission.isPositive()) {
            entries.add(LedgerEntry.credit(Account.PLATFORM_REVENUE, null, commission));
        }
        ledger.lockWallet(charge.renterId());
        if (reservations.shrinkForSettlement(charge.sessionId(), amount, charge.txnId())) {
            entries.addAll(0, ledger.draw(charge.renterId(), amount));
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
        ledger.lockWallet(renterId);
        entries.addAll(0, giveBack(sessionId, renterId, txnId, total));
        ledger.post(new LedgerTransaction(txnId, REFUND_REF, sessionId, entries));
    }

    /**
     * The credits that give {@code amount} of a session's charge back to the lots it drew from: bought lots first (the
     * last spent), then free ones; a free lot that has expired gets its part back as a new free lot, which expires as a
     * grant does, so given-back time is never lost to an expiry the renter couldn't have avoided.
     */
    private List<LedgerEntry> giveBack(UUID sessionId, UUID renterId, UUID txnId, Paise amount) {
        var drawn = ledger.drawnBy(renterId, sessionId, List.of(CHARGE_REF, REFUND_REF));
        var lots = drawn.keySet().stream()
                .map(ledger::lot)
                .sorted(Ledger.SPENDING_ORDER.reversed())
                .toList();
        var now = clock.instant();
        var entries = new ArrayList<LedgerEntry>();
        var expired = Paise.ZERO;
        var left = amount;
        for (var lot : lots) {
            if (!left.isPositive()) {
                break;
            }
            var back = drawn.get(lot.id()).isGreaterThan(left) ? left : drawn.get(lot.id());
            left = left.minus(back);
            if (lot.expiresAt() != null && !lot.expiresAt().isAfter(now)) {
                expired = expired.plus(back);
            } else {
                entries.add(LedgerEntry.creditLot(lot.account(), renterId, back, lot.id()));
            }
        }
        // Drawn before lots existed (V49), or more than the lots show: back to a new free lot like the expired part.
        expired = expired.plus(left);
        if (expired.isPositive()) {
            var lot = ledger.openLot(
                    renterId,
                    CreditLot.Kind.FREE,
                    REFUND_REF,
                    txnId,
                    expired,
                    now.plus(legal.credit().freeExpiresAfter()));
            entries.add(LedgerEntry.creditLot(Account.RENTER_WALLET, renterId, expired, lot.id()));
        }
        entries.sort(Comparator.comparing(LedgerEntry::account));
        return entries;
    }
}
