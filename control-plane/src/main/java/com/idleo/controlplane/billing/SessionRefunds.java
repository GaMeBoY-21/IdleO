package com.idleo.controlplane.billing;

import com.idleo.controlplane.Paise;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Giving back part or all of what a session cost, when an admin resolves a renter's report with a refund (ADR 0028).
 * What a session paid is read from its own ledger entries and nothing else, so a refund never exceeds what the renter
 * paid for it, net of everything given back before. A refund comes out of the platform's commission first, and only
 * then out of the hosts' shares, in proportion to what each kept; it's one balanced transaction that credits the
 * renter's wallet and debits the platform's revenue and each host's payable. The trust module calls it.
 */
public interface SessionRefunds {

    /** One host's part: what it kept of the session's charge, or what it gives back of it. */
    record HostPart(UUID hostId, Paise amount) {}

    /**
     * What the session's ledger entries come to now.
     *
     * @param renterPaid what the renter paid, less everything given back: settlements, lost time and refunds
     * @param commission what the platform kept of it
     * @param hosts what each host kept, largest first; together with the commission, what the renter paid
     */
    record Paid(Paise renterPaid, Paise commission, List<HostPart> hosts) {

        public static final Paid NOTHING = new Paid(Paise.ZERO, Paise.ZERO, List.of());
    }

    /**
     * Where a refund comes from.
     *
     * @param fromCommission the platform's part, taken first
     * @param fromHosts each host's part of the rest; a host that gives nothing back isn't listed
     */
    record Split(Paise amount, Paise fromCommission, List<HostPart> fromHosts) {

        public Paise fromHostsTotal() {
            return fromHosts.stream().map(HostPart::amount).reduce(Paise.ZERO, Paise::plus);
        }
    }

    /** A refund that was posted, under its ledger transaction. */
    record Refund(UUID txnId, Split split) {}

    /** What the session's ledger entries come to now; {@link Paid#NOTHING} for a session that was never charged. */
    Paid paid(UUID sessionId);

    /**
     * Refunds {@code amount} of the session's charge to its renter, under {@code txnId}, which is derived from what
     * caused it. Runs only inside the caller's database transaction, under the renter's wallet lock; the same {@code
     * txnId} again posts nothing and returns the first refund.
     *
     * @throws IllegalArgumentException when the amount isn't above zero, is more than the renter paid net, or the
     *     session isn't the renter's
     */
    Refund refund(UUID sessionId, UUID renterId, UUID txnId, Paise amount);

    /** What reports have given back of the session in all, which its renter's view takes off its charge. */
    Paise refunded(UUID sessionId);

    /**
     * Splits a refund of {@code amount}: the commission first, then the hosts' shares in proportion to what each kept,
     * rounded down, with the paise left over going one each to the hosts whose shares lost the most to rounding.
     * Never takes more from anyone than they kept.
     *
     * @throws IllegalArgumentException when the amount isn't above zero or is more than the renter paid
     */
    static Split split(Paid paid, Paise amount) {
        if (!amount.isPositive() || amount.isGreaterThan(paid.renterPaid())) {
            throw new IllegalArgumentException("a refund is more than zero and at most what the renter paid");
        }
        var commission = paid.commission().isPositive() ? paid.commission() : Paise.ZERO;
        var fromCommission = amount.isGreaterThan(commission) ? commission : amount;
        var rest = amount.minus(fromCommission);
        var hosts =
                paid.hosts().stream().filter(host -> host.amount().isPositive()).toList();
        long kept = hosts.stream().mapToLong(host -> host.amount().value()).sum();
        if (rest.value() > kept) {
            throw new IllegalArgumentException("the hosts kept less than the refund needs from them");
        }
        record Share(UUID hostId, long floor, long remainder) {}
        var shares = new ArrayList<Share>();
        long left = rest.value();
        for (var host : hosts) {
            long exact = Math.multiplyExact(rest.value(), host.amount().value());
            shares.add(new Share(host.hostId(), exact / kept, exact % kept));
            left -= exact / kept;
        }
        shares.sort(Comparator.comparingLong(Share::remainder).reversed().thenComparing(Share::hostId));
        var fromHosts = new ArrayList<HostPart>();
        for (var share : shares) {
            long part = share.floor() + (left > 0 ? 1 : 0);
            if (left > 0) {
                left--;
            }
            if (part > 0) {
                fromHosts.add(new HostPart(share.hostId(), Paise.of(part)));
            }
        }
        fromHosts.sort(Comparator.comparing(HostPart::hostId));
        return new Split(amount, fromCommission, List.copyOf(fromHosts));
    }
}
