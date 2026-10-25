package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.HostPayouts;
import com.idleo.controlplane.identity.AccountStandings;
import com.idleo.controlplane.payouts.PayoutSettled;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One payout to one host (ADR 0040), database first:
 *
 * <ol>
 *   <li>{@link #create}: in one transaction, billing moves what's available (after recovering anything owed) into
 *       {@code payouts_in_flight}, with the tax deducted ({@link TaxDeduction}) and any fee, and our payout row is
 *       written as {@code created};
 *   <li>{@link #send}: RazorpayX is asked to pay it by UPI, with {@code reference_id} and {@code X-Payout-Idempotency}
 *       both our payout's id, so sending it again pays nothing twice. Out of reach, it's sent again by {@link #resend};
 *       refused, the money goes back;
 *   <li>{@link #apply}: what RazorpayX says next, from a webhook, the answer to the call, or {@link #poll}. Statuses
 *       only move forward; {@code processed} posts the money as paid and tells the host; {@code failed}, {@code
 *       rejected} and {@code cancelled} put it back in their earnings for the next run; {@code reversed} puts it back
 *       too and marks the UPI ID suspect, so nothing more goes to it until the host checks it again.
 * </ol>
 */
@Service
class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    /** RazorpayX's statuses before a payout leaves, in the order they come. */
    private static final Map<String, Integer> RANK = Map.of("created", 0, "pending", 1, "queued", 1, "processing", 2);

    static final List<String> OPEN = List.of("created", "queued", "pending", "processing");
    static final List<String> CAME_BACK = List.of("failed", "rejected", "cancelled");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final HostPayouts ledger;
    private final RazorpayXClient razorpayx;
    private final PayoutMethods methods;
    private final Pans pans;
    private final TaxDeduction tax;
    private final PayoutsProperties properties;
    private final LegalSettings legal;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final PayoutsMetrics metrics;
    private final AccountStandings standings;

    PayoutService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            HostPayouts ledger,
            RazorpayXClient razorpayx,
            PayoutMethods methods,
            Pans pans,
            TaxDeduction tax,
            PayoutsProperties properties,
            LegalSettings legal,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            PayoutsMetrics metrics,
            AccountStandings standings) {
        this.standings = standings;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ledger = ledger;
        this.razorpayx = razorpayx;
        this.methods = methods;
        this.pans = pans;
        this.tax = tax;
        this.properties = properties;
        this.legal = legal;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
    }

    record Payout(
            UUID id,
            UUID userId,
            @Nullable UUID runId,
            String kind,
            UUID methodId,
            String fundAccountId,
            long grossPaise,
            long tdsPaise,
            long feePaise,
            long netPaise,
            String tdsSection,
            int tdsBasisPoints,
            String financialYear,
            String status,
            @Nullable String razorpayxId,
            @Nullable String utr,
            @Nullable String statusReason,
            @Nullable String statusSource,
            @Nullable String statusDescription,
            @Nullable Instant earnedSince,
            int attempts,
            @Nullable String lastError,
            Instant createdAt,
            @Nullable Instant sentAt,
            @Nullable Instant processedAt,
            @Nullable Instant endedAt) {

        boolean open() {
            return OPEN.contains(status);
        }
    }

    /** Why a person can't be paid now, for the run's log and the admin's retry. */
    enum NotPaid {
        NO_METHOD,
        /** Suspended for misconduct as a host (ADR 0041): nothing is paid until the suspension is lifted. */
        ACCOUNT_HELD,
        ON_HOLD,
        NO_PAN,
        IN_PROGRESS,
        TOO_LITTLE
    }

    /** A payout made, or why none was. */
    record Outcome(@Nullable Payout payout, @Nullable NotPaid notPaid) {}

    /**
     * Moves what the person can be paid into a new payout, if they can be paid: a UPI ID payouts may go to now, a
     * verified PAN (unless {@code legal.tds.pay-without-pan}), no other payout on its way, and at least {@code minimum}
     * payable. Nothing is sent yet.
     */
    Outcome create(UUID userId, String kind, @Nullable UUID runId, Paise minimum) {
        return Optional.ofNullable(transactions.execute(status -> {
                    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                            .param("key", "payout:" + userId)
                            .query((rs, n) -> null)
                            .list();
                    var now = now();
                    if (standings.payoutsHeld(userId)) {
                        return new Outcome(null, NotPaid.ACCOUNT_HELD);
                    }
                    var method = methods.inUse(userId).orElse(null);
                    if (method == null
                            || method.fundAccountId() == null
                            || !method.state().equals("active")) {
                        return new Outcome(null, NotPaid.NO_METHOD);
                    }
                    if (!method.payable(now)) {
                        return new Outcome(null, NotPaid.ON_HOLD);
                    }
                    var pan = pans.find(userId).filter(Pans.Pan::verified).orElse(null);
                    if (pan == null && !legal.tds().payWithoutPan()) {
                        return new Outcome(null, NotPaid.NO_PAN);
                    }
                    if (open(userId).isPresent()) {
                        return new Outcome(null, NotPaid.IN_PROGRESS);
                    }
                    var id = ids.newId();
                    var deduction = new TaxDeduction.Deduction[1];
                    var started = ledger.start(id, userId, minimum, gross -> {
                        deduction[0] = tax.of(userId, gross, now, pan);
                        var afterTax = gross.minus(deduction[0].tds());
                        return new HostPayouts.Deductions(deduction[0].tds(), fee(afterTax));
                    });
                    if (started.isEmpty()) {
                        return new Outcome(null, NotPaid.TOO_LITTLE);
                    }
                    var payout = started.get();
                    jdbc.sql("""
                                    insert into payouts.payouts
                                        (id, user_id, run_id, kind, method_id, fund_account_id, gross_paise, tds_paise,
                                         fee_paise, net_paise, tds_section, tds_basis_points, financial_year, status,
                                         earned_since, created_at, updated_at)
                                    values (:id, :userId, :runId, :kind, :methodId, :fundAccountId, :gross, :tds,
                                            :fee, :net, :section, :basisPoints, :year, 'created', :earnedSince, :now,
                                            :now)
                                    """)
                            .param("id", id)
                            .param("userId", userId)
                            .param("runId", runId)
                            .param("kind", kind)
                            .param("methodId", method.id())
                            .param("fundAccountId", method.fundAccountId())
                            .param("gross", payout.gross().value())
                            .param("tds", payout.tds().value())
                            .param("fee", payout.fee().value())
                            .param("net", payout.net().value())
                            .param("section", deduction[0].section())
                            .param("basisPoints", deduction[0].basisPoints())
                            .param("year", FinancialYear.of(now).name())
                            .param(
                                    "earnedSince",
                                    payout.oldestEarning() == null ? null : Timestamp.from(payout.oldestEarning()))
                            .param("now", Timestamp.from(now))
                            .update();
                    metrics.status("created");
                    return new Outcome(find(id).orElseThrow(), null);
                }))
                .orElseThrow();
    }

    /** The owner's part of RazorpayX's charge (only when hosts pay it), never leaving less than RazorpayX's ₹1. */
    private Paise fee(Paise afterTax) {
        var fees = properties.fees();
        if (fees.paidBy() != PayoutsProperties.PaidBy.HOST || fees.hostFeePaise() == 0) {
            return Paise.ZERO;
        }
        return Paise.of(Math.max(0, Math.min(fees.hostFeePaise(), afterTax.value() - 100)));
    }

    /** Asks RazorpayX to pay a created payout; safe to call again, which is how {@link #resend} retries. */
    void send(UUID payoutId) {
        var payout = find(payoutId).orElseThrow();
        if (!payout.status().equals("created")) {
            return;
        }
        RazorpayXClient.Payout answer;
        try {
            answer = razorpayx.createPayout(payoutId.toString(), payout.fundAccountId(), payout.netPaise());
        } catch (RazorpayXClient.Unreachable unreachable) {
            log.warn("Payout {} couldn't be sent to RazorpayX yet: {}", payoutId, unreachable.getMessage());
            attempted(payoutId, unreachable.getMessage());
            return;
        } catch (RazorpayXClient.Refused refused) {
            log.warn("RazorpayX refused payout {}: {} {}", payoutId, refused.code, refused.getMessage());
            attempted(payoutId, refused.getMessage());
            transactions.executeWithoutResult(status -> cameBack(
                    lock(payoutId),
                    "failed",
                    refused.reason,
                    "business",
                    refused.getMessage(),
                    "RazorpayX didn't accept the payout: " + refused.getMessage()));
            return;
        }
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            update payouts.payouts set razorpayx_id = :rx, sent_at = coalesce(sent_at, :now),
                                attempts = attempts + 1, updated_at = :now
                            where id = :id
                            """)
                    .param("rx", answer.id())
                    .param("now", Timestamp.from(now()))
                    .param("id", payoutId)
                    .update();
            apply(lock(payoutId), answer);
        });
    }

    private void attempted(UUID payoutId, @Nullable String error) {
        jdbc.sql("""
                        update payouts.payouts set attempts = attempts + 1, last_error = :error, updated_at = :now
                        where id = :id
                        """)
                .param("error", error == null ? null : error.substring(0, Math.min(500, error.length())))
                .param("now", Timestamp.from(now()))
                .param("id", payoutId)
                .update();
    }

    /** RazorpayX's word on a payout, by its id: from a webhook or a fetch. Unknown ids change nothing. */
    void applyRazorpayX(RazorpayXClient.Payout answer) {
        transactions.executeWithoutResult(status -> {
            var id = jdbc.sql("select id from payouts.payouts where razorpayx_id = :rx")
                    .param("rx", answer.id())
                    .query(UUID.class)
                    .optional()
                    .or(() -> ours(answer.referenceId()));
            id.ifPresent(payoutId -> {
                jdbc.sql("update payouts.payouts set razorpayx_id = coalesce(razorpayx_id, :rx) where id = :id")
                        .param("rx", answer.id())
                        .param("id", payoutId)
                        .update();
                apply(lock(payoutId), answer);
            });
        });
    }

    private Optional<UUID> ours(@Nullable String referenceId) {
        if (referenceId == null) {
            return Optional.empty();
        }
        try {
            var id = UUID.fromString(referenceId);
            return find(id).map(Payout::id);
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
    }

    /**
     * Moves a payout to what RazorpayX says, forward only, once per status; the caller holds its row lock. A payout that
     * came back stays back; a processed one may still be reversed.
     */
    void apply(Payout payout, RazorpayXClient.Payout answer) {
        var to = answer.status();
        var from = payout.status();
        if (CAME_BACK.contains(from) || from.equals("reversed")) {
            return;
        }
        switch (to) {
            case "processed" -> {
                if (from.equals("processed")) {
                    if (answer.utr() != null && payout.utr() == null) {
                        update(payout.id(), "processed", answer, null);
                    }
                    return;
                }
                ledger.paid(payout.id());
                update(payout.id(), "processed", answer, "processed_at");
                var processed = find(payout.id()).orElseThrow();
                metrics.status("processed");
                metrics.paid(
                        payout.netPaise(),
                        payout.earnedSince() == null
                                ? Duration.ZERO
                                : Duration.between(payout.earnedSince(), processed.processedAt()));
                events.publishEvent(new PayoutSettled(
                        payout.id(), payout.userId(), "processed", payout.netPaise(), payout.tdsPaise(), null));
            }
            case "failed", "rejected", "cancelled" -> {
                if (from.equals("processed")) {
                    // Not a move RazorpayX documents; the reconciliation lists it for someone to look at.
                    log.warn("RazorpayX says processed payout {} is {}; left for the reconciliation", payout.id(), to);
                    return;
                }
                cameBack(
                        payout,
                        to,
                        answer.statusReason(),
                        answer.statusSource(),
                        answer.statusDescription(),
                        "The payment didn't go through"
                                + (answer.statusDescription() == null ? "" : ": " + answer.statusDescription())
                                + ". It's back in your earnings, and the next payout tries again.");
            }
            case "reversed" -> {
                ledger.returned(payout.id());
                update(payout.id(), "reversed", answer, "ended_at");
                methods.suspect(payout.methodId());
                metrics.status("reversed");
                events.publishEvent(new PayoutSettled(
                        payout.id(),
                        payout.userId(),
                        "reversed",
                        payout.netPaise(),
                        payout.tdsPaise(),
                        "Your bank sent the payment back. It's in your earnings again: check your UPI ID in Payout"
                                + " details before the next payout."));
            }
            default -> {
                var rank = RANK.get(to);
                if (rank != null && rank > RANK.getOrDefault(from, 3)) {
                    update(payout.id(), to, answer, null);
                    metrics.status(to);
                } else if (rank == null) {
                    log.warn("RazorpayX gave payout {} a status we don't know: {}", payout.id(), to);
                }
            }
        }
    }

    /** The money goes back to the person's earnings; they're told why. */
    private void cameBack(
            Payout payout,
            String status,
            @Nullable String reason,
            @Nullable String source,
            @Nullable String description,
            String why) {
        if (!payout.open()) {
            return;
        }
        ledger.returned(payout.id());
        jdbc.sql("""
                        update payouts.payouts
                        set status = :status, status_reason = :reason, status_source = :source,
                            status_description = :description, ended_at = :now, updated_at = :now
                        where id = :id
                        """)
                .param("status", status)
                .param("reason", reason)
                .param("source", source)
                .param("description", truncate(description))
                .param("now", Timestamp.from(now()))
                .param("id", payout.id())
                .update();
        metrics.status(status);
        events.publishEvent(
                new PayoutSettled(payout.id(), payout.userId(), "failed", payout.netPaise(), payout.tdsPaise(), why));
    }

    private void update(UUID id, String status, RazorpayXClient.Payout answer, @Nullable String stamp) {
        var now = Timestamp.from(now());
        jdbc.sql("""
                        update payouts.payouts
                        set status = :status, utr = coalesce(:utr, utr), status_reason = :reason,
                            status_source = :source, status_description = :description, updated_at = :now,
                            processed_at = case when :stamp = 'processed_at' then :now else processed_at end,
                            ended_at = case when :stamp = 'ended_at' then :now else ended_at end
                        where id = :id
                        """)
                .param("status", status)
                .param("utr", answer.utr())
                .param("reason", answer.statusReason())
                .param("source", answer.statusSource())
                .param("description", truncate(answer.statusDescription()))
                .param("now", now)
                .param("stamp", stamp == null ? "" : stamp)
                .param("id", id)
                .update();
    }

    private static @Nullable String truncate(@Nullable String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** Payouts RazorpayX never accepted (out of reach), sent again with the same idempotency key. */
    @Scheduled(fixedDelayString = "${payouts.send-every}", initialDelayString = "${payouts.send-every}")
    void resend() {
        if (!razorpayx.enabled()) {
            return;
        }
        var cutoff = clock.instant().minus(properties.sendEvery());
        jdbc.sql("""
                        select id from payouts.payouts where status = 'created' and updated_at < :cutoff
                        order by created_at limit 50
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query(UUID.class)
                .list()
                .forEach(this::send);
    }

    /**
     * The backstop for lost webhooks: a payout that hasn't moved for {@code payouts.poll-after} is fetched from
     * RazorpayX, and so is a processed one within a week, which may still be reversed.
     */
    @Scheduled(fixedDelayString = "${payouts.poll-every}", initialDelayString = "${payouts.poll-every}")
    void poll() {
        if (!razorpayx.enabled()) {
            return;
        }
        var cutoff = clock.instant().minus(properties.pollAfter());
        var stale = jdbc.sql("""
                        select razorpayx_id from payouts.payouts
                        where razorpayx_id is not null and updated_at < :cutoff
                          and status in ('queued', 'pending', 'processing')
                        order by updated_at limit 100
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .query(String.class)
                .list();
        for (var razorpayxId : stale) {
            try {
                applyRazorpayX(razorpayx.fetchPayout(razorpayxId));
            } catch (RazorpayXClient.Refused | RazorpayXClient.Unreachable e) {
                log.info("Couldn't fetch payout {} from RazorpayX: {}", razorpayxId, e.getMessage());
            }
        }
    }

    /** The person's payout on its way, if any. */
    Optional<Payout> open(UUID userId) {
        return jdbc.sql("select * from payouts.payouts where user_id = :userId and status in (:open)")
                .param("userId", userId)
                .param("open", OPEN)
                .query(PayoutService::payout)
                .optional();
    }

    Optional<Payout> find(UUID id) {
        return jdbc.sql("select * from payouts.payouts where id = :id")
                .param("id", id)
                .query(PayoutService::payout)
                .optional();
    }

    private Payout lock(UUID id) {
        return jdbc.sql("select * from payouts.payouts where id = :id for update")
                .param("id", id)
                .query(PayoutService::payout)
                .single();
    }

    /** The person's payouts, newest first, after {@code after}. */
    List<Payout> ofUser(UUID userId, @Nullable UUID after, int limit) {
        return jdbc.sql("select * from payouts.payouts where user_id = :userId"
                        + " and (cast(:after as uuid) is null or id < :after) order by id desc limit " + limit)
                .param("userId", userId)
                .param("after", after)
                .query(PayoutService::payout)
                .list();
    }

    /** Payouts for the admin console: by status ({@code all} for any), newest first. */
    List<Payout> list(String status, @Nullable UUID runId, @Nullable UUID after, int limit) {
        return jdbc.sql("select * from payouts.payouts where (:status = 'all' or status = :status)"
                        + " and (cast(:runId as uuid) is null or run_id = :runId)"
                        + " and (cast(:after as uuid) is null or id < :after) order by id desc limit " + limit)
                .param("status", status)
                .param("runId", runId)
                .param("after", after)
                .query(PayoutService::payout)
                .list();
    }

    static Payout payout(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Payout(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("kind"),
                rs.getObject("method_id", UUID.class),
                rs.getString("fund_account_id"),
                rs.getLong("gross_paise"),
                rs.getLong("tds_paise"),
                rs.getLong("fee_paise"),
                rs.getLong("net_paise"),
                rs.getString("tds_section"),
                rs.getInt("tds_basis_points"),
                rs.getString("financial_year"),
                rs.getString("status"),
                rs.getString("razorpayx_id"),
                rs.getString("utr"),
                rs.getString("status_reason"),
                rs.getString("status_source"),
                rs.getString("status_description"),
                instant(rs.getTimestamp("earned_since")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("sent_at")),
                instant(rs.getTimestamp("processed_at")),
                instant(rs.getTimestamp("ended_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
