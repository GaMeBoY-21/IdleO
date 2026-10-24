package com.idleo.controlplane.billing;

import com.idleo.controlplane.Paise;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The ledger's side of paying hosts (ADR 0040), for the payouts module, which owns everything about RazorpayX. Every
 * figure is read from the ledger and every movement is one named ledger transaction:
 *
 * <ul>
 *   <li>A session's earnings are pending for {@code legal.payouts.dispute-window} after the session's last charge, and
 *       while a report about it is open ({@link PayoutHolds}); then they're available.
 *   <li>{@link #start} recovers what the owner owes ({@code host_receivable}) from what's available, then moves the
 *       rest out of the machines' {@code host_payable} into {@code payouts_in_flight}, less the tax deducted ({@code
 *       tds_payable}) and any fee, before RazorpayX is asked to pay.
 *   <li>{@link #paid} moves it on to {@code payouts_paid}; {@link #returned} puts everything back where it came from.
 * </ul>
 *
 * Every method that moves money runs in the caller's database transaction, under a lock on the owner, so a refund to a
 * renter and a payout never race.
 */
public interface HostPayouts {

    /**
     * One session's earnings on one machine not yet available.
     *
     * @param availableAt when its dispute window ends; null while a report about it holds it
     */
    record PendingSession(
            UUID hostId,
            UUID sessionId,
            long amountPaise,
            @Nullable Instant availableAt) {

        public boolean held() {
            return availableAt == null;
        }
    }

    /**
     * Where an owner's earnings stand now.
     *
     * @param unpaidPaise everything their machines have earned and not been paid ({@code host_payable})
     * @param pendingPaise the part still in its dispute window or held by a report
     * @param availablePaise the part a payout may take, before what's owed is recovered from it
     * @param owedPaise what they owe back after a refund of earnings already paid out ({@code host_receivable})
     * @param inFlightPaise on its way to their UPI ID
     * @param paidPaise what has reached it, in all
     * @param pending the sessions not yet available, the soonest first and held ones last
     */
    record Position(
            long unpaidPaise,
            long pendingPaise,
            long availablePaise,
            long owedPaise,
            long inFlightPaise,
            long paidPaise,
            List<PendingSession> pending) {

        public Position {
            pending = List.copyOf(pending);
        }

        /** What a payout would pay now: what's available, less what's owed. */
        public long payablePaise() {
            return Math.max(0, availablePaise - owedPaise);
        }
    }

    /** What the caller deducts from a payout's gross: the tax deducted at source and the owner's part of the fee. */
    record Deductions(Paise tds, Paise fee) {}

    /**
     * A payout's money as {@link #start} moved it.
     *
     * @param oldestEarning when the oldest earning it carries was charged; null when it carries only money a payout
     *     brought back
     */
    record Started(
            UUID payoutId,
            Paise recovered,
            Paise gross,
            Paise tds,
            Paise fee,
            @Nullable Instant oldestEarning) {

        public Paise net() {
            return gross.minus(tds).minus(fee);
        }
    }

    Position position(UUID ownerId);

    /**
     * Recovers what the owner owes from what's available, then, when at least {@code minimum} is left, moves it into a
     * payout named {@code payoutId}: {@code deductions} is given the gross and says what to deduct. Runs only in the
     * caller's transaction. Empty when less than {@code minimum} is payable (anything recovered stays recovered).
     */
    Optional<Started> start(UUID payoutId, UUID ownerId, Paise minimum, Function<Paise, Deductions> deductions);

    /** RazorpayX processed the payout: the money left. Posting it again changes nothing. */
    void paid(UUID payoutId);

    /**
     * The payout failed, was rejected or cancelled, or came back from the bank: its gross goes back to the machines it
     * came from, with the tax and the fee, whether it was in flight or already paid. Posting it again changes nothing.
     */
    void returned(UUID payoutId);

    /**
     * The owner gave up what their machines earned and wasn't paid, in writing, to delete the account without a payout
     * method: it all goes to {@code earnings_forfeited}. Returns what was given up.
     */
    Paise forfeit(UUID forfeitId, UUID ownerId);
}
