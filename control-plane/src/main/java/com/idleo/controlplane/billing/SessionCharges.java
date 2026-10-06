package com.idleo.controlplane.billing;

import com.idleo.controlplane.Paise;
import java.util.List;
import java.util.UUID;

/**
 * Charging a session's minutes (ADR 0017), for metering. Each settlement is one balanced ledger transaction:
 * the renter's wallet is debited, each host's payable credited its share, and the platform's revenue credited
 * the commission. In the same database transaction the session's hold shrinks by the charge and is topped back
 * up. Both methods run only inside the caller's transaction, and a {@code txnId} posted before posts nothing.
 */
public interface SessionCharges {

    /** What one host earned in a settlement, before commission: its minutes times its price. */
    record Part(UUID hostId, Paise amount) {}

    /**
     * @param holdTarget what the hold is topped up to afterwards, at most: 30 minutes at the session's price,
     *     capped by what's left of its spending cap. The renter's available balance limits it further.
     */
    record Charge(UUID sessionId, UUID renterId, UUID txnId, List<Part> parts, Paise holdTarget) {}

    /** An amount split into the host's share and the platform's commission. */
    record Split(Paise amount, Paise hostShare, Paise commission) {}

    /** One host's part of a settlement: what it was charged for, and its share of that. */
    record HostShare(UUID hostId, Paise amount, Paise share) {}

    /** What a settlement charged, in all and for each host. */
    record Charged(Paise amount, Paise hostShare, Paise commission, List<HostShare> hosts) {}

    /** Settles a charge; its parts must add up to more than zero. */
    Charged settle(Charge charge);

    /** One host's share of a refund, in the proportions of the settlement it reverses. */
    record RefundPart(UUID hostId, Paise host, Paise platform) {}

    /**
     * Gives charged minutes back: credits the renter's wallet, and debits each host's payable and the platform's
     * revenue by their parts. The hold doesn't change.
     */
    void refund(UUID sessionId, UUID renterId, UUID txnId, List<RefundPart> parts);

    /** The platform's commission, in percent (execution plan line 311: 15–25 to test; 20 by default). */
    int commissionPercent();

    /** Splits {@code amount} into the host's share, rounded down, and the commission, which keeps the rest. */
    static Split split(Paise amount, int commissionPercent) {
        var hostShare = Paise.of(Math.multiplyExact(amount.value(), 100 - commissionPercent) / 100);
        return new Split(amount, hostShare, amount.minus(hostShare));
    }
}
