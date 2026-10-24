package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.billing.HostPayouts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the payouts module holds about a person (ADR 0040, docs/reference/personal-data-map.md): their PAN (sealed, shown by
 * its last four), the UPI IDs they checked, their payouts and anything they gave up in writing. Deleting an account
 * pays out what their machines earned first ({@link #beforeErasure}): what's available at once, outside the weekly
 * run, and what's pending once its dispute window ends; with no UPI ID payouts may go to, they add one or give the
 * earnings up in writing. The PAN and the payouts are money records the tax law keeps: kept by opaque id; the UPI IDs
 * and the names banks gave for them are cleared.
 */
@Component
class PayoutsPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final HostPayouts ledger;
    private final PayoutMethods methods;
    private final PayoutService payouts;
    private final PayoutRuns runs;
    private final Clock clock;

    PayoutsPersonalData(
            JdbcClient jdbc,
            ExportRows rows,
            HostPayouts ledger,
            PayoutMethods methods,
            PayoutService payouts,
            PayoutRuns runs,
            Clock clock) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.ledger = ledger;
        this.methods = methods;
        this.payouts = payouts;
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "payouts";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql("""
                        select count(*), min(created_at), max(created_at) from payouts.payouts where user_id = :user
                        """)
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "hosting", "payouts", rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part(
                        "money",
                        "pan",
                        rows.one(
                                jdbc,
                                "select pan_last4, name, holder, state, submitted_at, decided_at, reason"
                                        + " from payouts.pans where user_id = :user",
                                params)),
                new Part("hosting", "payoutMethods", rows.list(jdbc, """
                                select vpa, state, registered_name, name_match_score, checked_at, confirmed_at,
                                       hold_until, ended_at, failure, reason
                                from payouts.methods where user_id = :user order by checked_at
                                """, params)),
                new Part("hosting", "payouts", rows.list(jdbc, """
                                select id, kind, gross_paise, tds_paise, fee_paise, net_paise, tds_section,
                                       financial_year, status, utr, status_description, created_at, processed_at,
                                       ended_at
                                from payouts.payouts where user_id = :user order by created_at
                                """, params)),
                new Part(
                        "hosting",
                        "earningsGivenUp",
                        rows.list(
                                jdbc,
                                "select amount_paise, statement, created_at from payouts.forfeits"
                                        + " where user_id = :user order by created_at",
                                params)));
    }

    /** No UPI ID payouts may go to: a machine's earnings can't reach them before the account goes. */
    private boolean noMethod(UUID userId) {
        var inUse = methods.inUse(userId);
        return (inUse.isEmpty() || !inUse.get().state().equals("active"))
                && methods.inReview(userId).isEmpty();
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var blockers = new ArrayList<Blocker>();
        var position = ledger.position(userId);
        if (position.unpaidPaise() > 0 && noMethod(userId)) {
            blockers.add(new Blocker(
                    "payout-method-needed",
                    "Your machines have earned " + Rupees.format(position.unpaidPaise())
                            + " that hasn't been paid out. Add a UPI ID in Hosting, Payout details, so we can pay it"
                            + " before the account goes, or give it up in writing there.",
                    position.unpaidPaise()));
            return blockers;
        }
        if (!finalRequested(userId)) {
            return blockers;
        }
        var open = payouts.open(userId);
        if (open.isPresent()) {
            blockers.add(new Blocker(
                    "final-payout-in-progress",
                    Rupees.format(open.get().netPaise()) + " is on its way to your UPI ID. The account is deleted"
                            + " once it's there.",
                    open.get().netPaise()));
        } else if (position.pendingPaise() > 0 || position.payablePaise() >= PayoutRuns.RAZORPAYX_MINIMUM.value()) {
            // Less than RazorpayX's ₹1 can't be paid out; it stays a money record.
            var held = position.pending().stream().anyMatch(HostPayouts.PendingSession::held);
            blockers.add(new Blocker(
                    position.pendingPaise() > 0 ? "earnings-pending" : "final-payout-waiting",
                    position.pendingPaise() > 0
                            ? Rupees.format(position.pendingPaise())
                                    + (held
                                            ? " waits for a report about one of your sessions to be answered."
                                            : " becomes available after its dispute window, and is paid then.")
                            : Rupees.format(position.unpaidPaise())
                                    + " is waiting to be paid to your UPI ID. If a payout came back, check your UPI ID"
                                    + " in Payout details.",
                    position.unpaidPaise()));
        }
        return blockers;
    }

    @Override
    public List<Wait> waits(UUID userId) {
        var position = ledger.position(userId);
        var waits = new ArrayList<Wait>();
        if (position.availablePaise() > 0) {
            waits.add(new Wait(
                    "final-payout",
                    Rupees.format(position.availablePaise()) + " your machines earned is paid to your UPI ID when the"
                            + " waiting period ends, before the account is deleted.",
                    position.availablePaise(),
                    null));
        }
        if (position.pendingPaise() > 0) {
            var until = position.pending().stream()
                    .map(HostPayouts.PendingSession::availableAt)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);
            boolean held = position.pending().stream().anyMatch(HostPayouts.PendingSession::held);
            waits.add(new Wait(
                    "earnings-pending",
                    Rupees.format(position.pendingPaise())
                            + (held
                                    ? " waits for a report about one of your sessions to be answered, then is paid;"
                                    : " is still in its dispute window and is paid once it's available;")
                            + " the account is deleted after that.",
                    position.pendingPaise(),
                    held ? null : until));
        }
        return waits;
    }

    /** The erasure's first look: pay what's available at once, outside the weekly run (ADR 0040). */
    @Override
    public void beforeErasure(UUID userId) {
        jdbc.sql("""
                        insert into payouts.final_payouts (user_id, requested_at) values (:user, :now)
                        on conflict (user_id) do nothing
                        """)
                .param("user", userId)
                .param("now", Timestamp.from(clock.instant()))
                .update();
        if (payouts.open(userId).isEmpty() && ledger.position(userId).payablePaise() >= 100) {
            runs.payFinal(userId);
        }
    }

    private boolean finalRequested(UUID userId) {
        return jdbc.sql("select exists (select 1 from payouts.final_payouts where user_id = :user)")
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    /** The UPI IDs and banks' names for them go; the PAN, the payouts and what was given up stay, by opaque id. */
    @Override
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DE_IDENTIFY) {
            return;
        }
        jdbc.sql("update payouts.methods set vpa = null, registered_name = null where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from payouts.final_payouts where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from payouts.idempotency_keys where user_id = :user")
                .param("user", userId)
                .update();
    }
}
