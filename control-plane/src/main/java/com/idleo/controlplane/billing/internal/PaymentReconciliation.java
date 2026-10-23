package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Razorpay against our ledger, a day at a time (ADR 0039): the day's payments and refunds are listed from Razorpay's API
 * and compared one by one with our top-ups and refund parts, and so are ours from that day that Razorpay didn't list.
 * Each disagreement is an item for the admin console, kept once by its kind and Razorpay's id, so running the same day
 * again adds nothing; an item a later run finds right is {@code fixed}, and an admin may accept one with a reason.
 *
 * <p>Two disagreements are also put right here, by the same paths a webhook takes, and still listed so someone asks why
 * the webhook didn't: a captured payment whose top-up wasn't credited is credited ({@link TopUps#credit}, which checks
 * it as it checks every payment), and a refund Razorpay has finished that we hadn't heard of is settled. Nothing else
 * moves money: a payment authorised but not captured is never credited.
 *
 * <p>{@code billing.payments.reconciliation.open} is the open items, which must be 0; {@code
 * billing.payments.reconciliation.last_success} when the last run finished.
 */
@Component
class PaymentReconciliation {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);

    private final RazorpayClient razorpay;
    private final RazorpayProperties settings;
    private final TopUps topUps;
    private final CreditRefunds refunds;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final AuditTrail audit;
    private final Clock clock;
    private final AtomicLong open = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    PaymentReconciliation(
            RazorpayClient razorpay,
            RazorpayProperties settings,
            TopUps topUps,
            CreditRefunds refunds,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            AuditTrail audit,
            Clock clock,
            MeterRegistry registry) {
        this.razorpay = razorpay;
        this.settings = settings;
        this.topUps = topUps;
        this.refunds = refunds;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
        Gauge.builder("billing.payments.reconciliation.open", open, AtomicLong::get)
                .description("Disagreements between Razorpay and our ledger that nobody has closed; must be 0")
                .register(registry);
        Gauge.builder("billing.payments.reconciliation.last_success", lastSuccessEpochSeconds, AtomicLong::get)
                .description("When the last payment reconciliation finished, in Unix seconds")
                .register(registry);
    }

    enum Kind {
        /** Razorpay has a payment or refund that isn't one of ours. */
        MISSING_OURS,
        /** We have one Razorpay doesn't. */
        MISSING_THEIRS,
        AMOUNT,
        STATUS,
        /** Razorpay captured a payment that no top-up was credited for. */
        CAPTURED_NOT_CREDITED,
        /** A payment authorised and not captured: auto-capture should have captured it. Never credited. */
        AUTHORIZED_NOT_CAPTURED;

        String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One disagreement, about Razorpay's {@code subject} (a payment or refund id). */
    record Found(
            Kind kind,
            String subject,
            @Nullable String paymentId,
            @Nullable UUID topUpId,
            @Nullable UUID userId,
            @Nullable Long ourPaise,
            @Nullable Long theirPaise,
            @Nullable String ourStatus,
            @Nullable String theirStatus,
            String detail) {}

    record Run(
            UUID id,
            LocalDate day,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer payments,
            @Nullable Integer refunds,
            @Nullable Integer found,
            @Nullable Integer open,
            @Nullable String failure) {}

    record Mismatch(
            UUID id,
            String kind,
            String subject,
            @Nullable String paymentId,
            @Nullable UUID topUpId,
            @Nullable UUID userId,
            @Nullable Long ourPaise,
            @Nullable Long theirPaise,
            @Nullable String ourStatus,
            @Nullable String theirStatus,
            String detail,
            String state,
            Instant foundAt,
            Instant lastFoundAt,
            @Nullable Instant closedAt,
            @Nullable String closedBy,
            @Nullable String closeReason) {}

    /** Yesterday in India, once a day; one instance runs it, whichever claims the day first. */
    @Scheduled(cron = "${billing.payment-reconciliation.cron}", zone = "Asia/Kolkata")
    void nightly() {
        if (!settings.enabled()) {
            return;
        }
        var day = LocalDate.ofInstant(clock.instant(), INDIA).minusDays(1);
        try {
            run(day, "nightly", null);
        } catch (DuplicateKeyException claimed) {
            log.info("Payment reconciliation for {} is another instance's", day);
        }
    }

    /**
     * Runs a day ({@code null}: today in India) now, for an admin; the run and who asked are audited in the run's own
     * transaction.
     */
    Run run(@Nullable LocalDate asked, AuditTrail.Actor actor, String reason) {
        if (!settings.enabled()) {
            throw new IllegalArgumentException("Razorpay isn't configured on this server");
        }
        var today = LocalDate.ofInstant(clock.instant(), INDIA);
        var day = asked == null ? today : asked;
        if (day.isAfter(today)) {
            throw new IllegalArgumentException(day + " hasn't happened yet");
        }
        return run(day, actor.label(), new Asked(actor, reason));
    }

    private record Asked(AuditTrail.Actor actor, String reason) {}

    private Run run(LocalDate day, String trigger, @Nullable Asked asked) {
        var runId = ids.newId();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into billing.payment_reconciliation_runs (id, day, trigger, started_at)
                            values (:id, :day, :trigger, :now)
                            """)
                    .param("id", runId)
                    .param("day", day)
                    .param("trigger", trigger)
                    .param("now", Timestamp.from(now()))
                    .update();
            if (asked != null) {
                audit.record(new AuditTrail.Entry(
                        asked.actor(),
                        "payments.reconcile",
                        "payment_reconciliation_run",
                        runId.toString(),
                        asked.reason(),
                        null,
                        Map.of("day", day.toString())));
            }
        });
        var from = day.atStartOfDay(INDIA).toInstant();
        var to = day.plusDays(1).atStartOfDay(INDIA).toInstant();
        List<RazorpayClient.Payment> payments;
        List<RazorpayClient.Refund> theirRefunds;
        var found = new ArrayList<Found>();
        var examined = new HashSet<String>();
        try {
            payments = razorpay.payments(from, to);
            theirRefunds = razorpay.refunds(from, to);
            for (var payment : payments) {
                examined.add(payment.id());
                comparePayment(payment).ifPresent(found::add);
            }
            var listed = payments.stream().map(RazorpayClient.Payment::id).collect(java.util.stream.Collectors.toSet());
            for (var ours : paidBetween(from, to)) {
                if (!listed.contains(ours.paymentId())) {
                    examined.add(Objects.requireNonNull(ours.paymentId()));
                    checkOurs(ours).ifPresent(found::add);
                }
            }
            for (var refund : theirRefunds) {
                examined.add(refund.id());
                compareRefund(refund).ifPresent(found::add);
            }
            var listedRefunds =
                    theirRefunds.stream().map(RazorpayClient.Refund::id).collect(java.util.stream.Collectors.toSet());
            for (var part : partsBetween(from, to)) {
                var refundId = Objects.requireNonNull(part.razorpayRefundId());
                if (!listedRefunds.contains(refundId)) {
                    examined.add(refundId);
                    checkOurPart(part).ifPresent(found::add);
                }
            }
        } catch (RazorpayClient.Refused | RazorpayClient.Unreachable failure) {
            log.error("Payment reconciliation for {} couldn't finish: {}", day, failure.getMessage());
            jdbc.sql("""
                            update billing.payment_reconciliation_runs set failure = :failure, finished_at = :now
                            where id = :id
                            """)
                    .param("failure", "Razorpay: " + failure.getMessage())
                    .param("now", Timestamp.from(now()))
                    .param("id", runId)
                    .update();
            return find(runId).orElseThrow();
        }
        int stillOpen = Objects.requireNonNull(transactions.execute(status -> keep(runId, found, examined)));
        jdbc.sql("""
                        update billing.payment_reconciliation_runs
                        set payments = :payments, refunds = :refunds, found = :found, open = :open, finished_at = :now
                        where id = :id
                        """)
                .param("payments", payments.size())
                .param("refunds", theirRefunds.size())
                .param("found", found.size())
                .param("open", stillOpen)
                .param("now", Timestamp.from(now()))
                .param("id", runId)
                .update();
        open.set(stillOpen);
        lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        for (var mismatch : found) {
            log.error(
                    "Payment reconciliation for {}: {} on {}: {}",
                    day,
                    mismatch.kind().dbValue(),
                    mismatch.subject(),
                    mismatch.detail());
        }
        log.info("Payment reconciliation for {} finished: {} found, {} open", day, found.size(), stillOpen);
        return find(runId).orElseThrow();
    }

    /** One of Razorpay's payments against the top-up of its order. */
    private Optional<Found> comparePayment(RazorpayClient.Payment payment) {
        var topUp = payment.orderId() == null
                ? null
                : topUps.byOrder(payment.orderId()).orElse(null);
        if (topUp == null) {
            if (payment.status().equals("failed")) {
                return Optional.empty();
            }
            return Optional.of(found(
                    Kind.MISSING_OURS,
                    payment.id(),
                    payment,
                    null,
                    null,
                    "Razorpay has a " + payment.status() + " payment for order " + payment.orderId()
                            + ", which isn't one of our top-ups."));
        }
        if (payment.amount() != topUp.amount().value() || !"INR".equals(payment.currency())) {
            return Optional.of(found(
                    Kind.AMOUNT,
                    payment.id(),
                    payment,
                    topUp,
                    topUp.status().dbValue(),
                    "Razorpay's payment is " + payment.amount() + " " + payment.currency() + "; the top-up is "
                            + topUp.amount().value() + " INR."));
        }
        boolean ours = payment.id().equals(topUp.paymentId());
        switch (payment.status()) {
            case "authorized" -> {
                return Optional.of(found(
                        Kind.AUTHORIZED_NOT_CAPTURED,
                        payment.id(),
                        payment,
                        topUp,
                        topUp.status().dbValue(),
                        "Authorised and not captured: check auto-capture in Razorpay's dashboard. Nothing is"
                                + " credited for it."));
            }
            case "captured", "refunded" -> {
                if (ours) {
                    return refundedMismatch(payment, topUp);
                }
                if (topUp.status() == TopUps.Status.PAID) {
                    return Optional.of(found(
                            Kind.CAPTURED_NOT_CREDITED,
                            payment.id(),
                            payment,
                            topUp,
                            topUp.status().dbValue(),
                            "A second captured payment on an order already paid by " + topUp.paymentId()
                                    + ": refund it from Razorpay's dashboard."));
                }
                var outcome = credit(topUp, payment);
                return Optional.of(found(
                        Kind.CAPTURED_NOT_CREDITED,
                        payment.id(),
                        payment,
                        topUp,
                        topUp.status().dbValue(),
                        outcome == TopUps.Outcome.CREDITED
                                ? "Captured and not credited: the reconciliation credited it now. Check why the"
                                        + " webhook didn't."
                                : "Captured and not credited, and it couldn't be credited now ("
                                        + outcome.name().toLowerCase(java.util.Locale.ROOT) + ")."));
            }
            case "failed" -> {
                if (ours) {
                    return Optional.of(found(
                            Kind.STATUS,
                            payment.id(),
                            payment,
                            topUp,
                            topUp.status().dbValue(),
                            "Razorpay says the payment failed; we credited it."));
                }
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private TopUps.Outcome credit(TopUps.TopUp topUp, RazorpayClient.Payment payment) {
        try {
            return topUps.credit(topUp, payment, "reconciliation");
        } catch (ApiProblem refused) {
            return TopUps.Outcome.NOT_CAPTURED;
        }
    }

    /** What Razorpay has refunded of a payment against the refund parts it accepted from us. */
    private Optional<Found> refundedMismatch(RazorpayClient.Payment payment, TopUps.TopUp topUp) {
        long ours =
                jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) from billing.refund_parts
                        where payment_id = :paymentId and status in ('pending', 'processed')
                        """).param("paymentId", payment.id()).query(Long.class).single();
        if (ours == payment.amountRefunded()) {
            return Optional.empty();
        }
        return Optional.of(new Found(
                Kind.AMOUNT,
                payment.id(),
                payment.id(),
                topUp.id(),
                topUp.userId(),
                ours,
                payment.amountRefunded(),
                topUp.status().dbValue(),
                payment.status(),
                "Razorpay has refunded " + payment.amountRefunded()
                        + " paise of the payment; our refunds of it come to " + ours + " paise."));
    }

    /** A top-up we credited that Razorpay didn't list for its day: asked for by id. */
    private Optional<Found> checkOurs(TopUps.TopUp topUp) {
        var paymentId = Objects.requireNonNull(topUp.paymentId());
        RazorpayClient.Payment payment;
        try {
            payment = razorpay.fetchPayment(paymentId);
        } catch (RazorpayClient.Refused refused) {
            return Optional.of(new Found(
                    Kind.MISSING_THEIRS,
                    paymentId,
                    paymentId,
                    topUp.id(),
                    topUp.userId(),
                    topUp.amount().value(),
                    null,
                    topUp.status().dbValue(),
                    null,
                    "We credited this payment; Razorpay doesn't know it (" + refused.getMessage() + ")."));
        }
        // Created on another day, so not in the day's list: compared as one of theirs.
        return comparePayment(payment);
    }

    /** One of Razorpay's refunds against our part with its id or receipt. */
    private Optional<Found> compareRefund(RazorpayClient.Refund refund) {
        var part = jdbc.sql("""
                        select p.*, r.user_id from billing.refund_parts p
                        join billing.credit_refunds r on r.id = p.refund_id
                        where p.razorpay_refund_id = :id or p.receipt = :receipt
                        """)
                .param("id", refund.id())
                .param("receipt", refund.receipt() == null ? "" : refund.receipt())
                .query(PaymentReconciliation::part)
                .optional();
        if (part.isEmpty()) {
            return Optional.of(new Found(
                    Kind.MISSING_OURS,
                    refund.id(),
                    refund.paymentId(),
                    null,
                    null,
                    null,
                    refund.amount(),
                    null,
                    refund.status(),
                    "Razorpay has a refund of payment " + refund.paymentId() + " that we didn't make."));
        }
        var ours = part.get();
        if (ours.amount() != refund.amount() || !"INR".equals(refund.currency())) {
            return Optional.of(refundFound(
                    Kind.AMOUNT,
                    refund,
                    ours,
                    "Razorpay refunded " + refund.amount() + " " + refund.currency() + "; we asked for " + ours.amount()
                            + " INR."));
        }
        boolean theirsFinal =
                refund.status().equals("processed") || refund.status().equals("failed");
        if (theirsFinal && !refund.status().equals(ours.status())) {
            boolean wasOpen = ours.status().equals("sending") || ours.status().equals("pending");
            if (wasOpen) {
                // The webhook didn't reach us: settled now by the same path it takes.
                refunds.fromWebhook(refund.id(), refund.receipt(), refund.status());
            }
            return Optional.of(refundFound(
                    Kind.STATUS,
                    refund,
                    ours,
                    wasOpen
                            ? "Razorpay's refund is " + refund.status() + " and we hadn't heard: settled it now."
                                    + " Check why the webhook didn't arrive."
                            : "Razorpay's refund is " + refund.status() + "; ours is " + ours.status() + "."));
        }
        return Optional.empty();
    }

    /** A part Razorpay accepted that wasn't in the day's list: looked for among its payment's refunds. */
    private Optional<Found> checkOurPart(OurPart part) {
        var refundId = Objects.requireNonNull(part.razorpayRefundId());
        var theirs = razorpay.refundsOf(part.paymentId()).stream()
                .filter(refund -> refund.id().equals(refundId))
                .findFirst();
        if (theirs.isEmpty()) {
            return Optional.of(new Found(
                    Kind.MISSING_THEIRS,
                    refundId,
                    part.paymentId(),
                    part.topUpId(),
                    part.userId(),
                    part.amount(),
                    null,
                    part.status(),
                    null,
                    "We have this refund of payment " + part.paymentId() + "; Razorpay doesn't."));
        }
        return compareRefund(theirs.get());
    }

    private Found found(
            Kind kind,
            String subject,
            RazorpayClient.Payment payment,
            TopUps.@Nullable TopUp topUp,
            @Nullable String ourStatus,
            String detail) {
        return new Found(
                kind,
                subject,
                payment.id(),
                topUp == null ? null : topUp.id(),
                topUp == null ? null : topUp.userId(),
                topUp == null ? null : topUp.amount().value(),
                payment.amount(),
                ourStatus,
                payment.status(),
                detail);
    }

    private static Found refundFound(Kind kind, RazorpayClient.Refund refund, OurPart ours, String detail) {
        return new Found(
                kind,
                refund.id(),
                refund.paymentId(),
                ours.topUpId(),
                ours.userId(),
                ours.amount(),
                refund.amount(),
                ours.status(),
                refund.status(),
                detail);
    }

    /**
     * Keeps what the run found, once each by kind and subject (found again: still the same item, reopened unless an
     * admin accepted it), and marks fixed every open item about something this run examined and found right. Returns
     * how many are open.
     */
    private int keep(UUID runId, List<Found> found, Set<String> examined) {
        var now = Timestamp.from(now());
        var foundKeys = new HashSet<String>();
        for (var item : found) {
            foundKeys.add(item.kind().dbValue() + "|" + item.subject());
            jdbc.sql("""
                            insert into billing.payment_mismatches
                                (id, kind, subject, payment_id, topup_id, user_id, our_paise, their_paise, our_status,
                                 their_status, detail, state, first_run_id, last_run_id, found_at, last_found_at)
                            values (:id, :kind, :subject, :paymentId, :topUpId, :userId, :ours, :theirs, :ourStatus,
                                    :theirStatus, :detail, 'open', :runId, :runId, :now, :now)
                            on conflict (kind, subject) do update
                            set our_paise = excluded.our_paise, their_paise = excluded.their_paise,
                                our_status = excluded.our_status, their_status = excluded.their_status,
                                detail = excluded.detail, last_run_id = excluded.last_run_id,
                                last_found_at = excluded.last_found_at,
                                state = case when billing.payment_mismatches.state = 'accepted' then 'accepted'
                                             else 'open' end,
                                closed_at = case when billing.payment_mismatches.state = 'accepted'
                                                 then billing.payment_mismatches.closed_at end,
                                closed_by = case when billing.payment_mismatches.state = 'accepted'
                                                 then billing.payment_mismatches.closed_by end,
                                close_reason = case when billing.payment_mismatches.state = 'accepted'
                                                    then billing.payment_mismatches.close_reason end
                            """)
                    .param("id", ids.newId())
                    .param("kind", item.kind().dbValue())
                    .param("subject", item.subject())
                    .param("paymentId", item.paymentId())
                    .param("topUpId", item.topUpId())
                    .param("userId", item.userId())
                    .param("ours", item.ourPaise())
                    .param("theirs", item.theirPaise())
                    .param("ourStatus", item.ourStatus())
                    .param("theirStatus", item.theirStatus())
                    .param(
                            "detail",
                            item.detail().length() > 500 ? item.detail().substring(0, 500) : item.detail())
                    .param("runId", runId)
                    .param("now", now)
                    .update();
        }
        var stale = jdbc.sql("select id, kind, subject from billing.payment_mismatches where state = 'open'")
                .query((rs, n) -> new String[] {
                    rs.getString("id"), rs.getString("kind") + "|" + rs.getString("subject"), rs.getString("subject")
                })
                .list();
        for (var item : stale) {
            if (examined.contains(item[2]) && !foundKeys.contains(item[1])) {
                jdbc.sql("""
                                update billing.payment_mismatches
                                set state = 'fixed', closed_at = :now, closed_by = 'reconciliation',
                                    close_reason = 'a later run found it right'
                                where id = :id::uuid and state = 'open'
                                """).param("now", now).param("id", item[0]).update();
            }
        }
        return openCount();
    }

    /** An admin closes an item they've looked into, with why; audited in the same transaction. */
    Mismatch accept(UUID id, String reason, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var item = jdbc.sql("select * from billing.payment_mismatches where id = :id for update")
                    .param("id", id)
                    .query(PaymentReconciliation::mismatch)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("there's no mismatch " + id));
            if (!item.state().equals("open")) {
                throw new IllegalArgumentException("the mismatch is " + item.state() + " already");
            }
            jdbc.sql("""
                            update billing.payment_mismatches
                            set state = 'accepted', closed_at = :now, closed_by = :by, close_reason = :reason
                            where id = :id
                            """)
                    .param("now", Timestamp.from(now()))
                    .param("by", actor.label())
                    .param("reason", reason.strip())
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "payments.mismatch.accept",
                    "payment_mismatch",
                    id.toString(),
                    reason.strip(),
                    null,
                    Map.of("kind", item.kind(), "subject", item.subject())));
            open.set(openCount());
            return mismatch(id).orElseThrow();
        }));
    }

    /** Keeps the open count right on every instance, not just the one that ran. */
    @Scheduled(fixedDelayString = "${billing.payment-reconciliation.count-every}")
    void count() {
        open.set(openCount());
    }

    private int openCount() {
        return jdbc.sql("select count(*) from billing.payment_mismatches where state = 'open'")
                .query(Integer.class)
                .single();
    }

    /** Our top-ups credited in the window. */
    private List<TopUps.TopUp> paidBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        select * from billing.topups where status = 'paid' and paid_at >= :from and paid_at < :to
                        order by paid_at
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(TopUps::topUp)
                .list();
    }

    private record OurPart(
            UUID id,
            String paymentId,
            UUID topUpId,
            UUID userId,
            long amount,
            String status,
            @Nullable String razorpayRefundId) {}

    /** Our refund parts Razorpay accepted in the window. */
    private List<OurPart> partsBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        select p.*, r.user_id from billing.refund_parts p
                        join billing.credit_refunds r on r.id = p.refund_id
                        where p.razorpay_refund_id is not null and p.created_at >= :from and p.created_at < :to
                        order by p.created_at
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(PaymentReconciliation::part)
                .list();
    }

    Optional<Run> find(UUID id) {
        return jdbc.sql("select * from billing.payment_reconciliation_runs where id = :id")
                .param("id", id)
                .query(PaymentReconciliation::run)
                .optional();
    }

    List<Run> runs(int limit) {
        return jdbc.sql("""
                        select * from billing.payment_reconciliation_runs order by started_at desc, id desc limit :limit
                        """)
                .param("limit", limit)
                .query(PaymentReconciliation::run)
                .list();
    }

    Optional<Mismatch> mismatch(UUID id) {
        return jdbc.sql("select * from billing.payment_mismatches where id = :id")
                .param("id", id)
                .query(PaymentReconciliation::mismatch)
                .optional();
    }

    /** {@code state}: open, fixed, accepted or all; the newest found first. */
    List<Mismatch> mismatches(String state, int limit) {
        return jdbc.sql("""
                        select * from billing.payment_mismatches where (:state = 'all' or state = :state)
                        order by last_found_at desc, id desc limit :limit
                        """)
                .param("state", state)
                .param("limit", limit)
                .query(PaymentReconciliation::mismatch)
                .list();
    }

    private static OurPart part(ResultSet rs, int row) throws SQLException {
        return new OurPart(
                rs.getObject("id", UUID.class),
                rs.getString("payment_id"),
                rs.getObject("topup_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getLong("amount_paise"),
                rs.getString("status"),
                rs.getString("razorpay_refund_id"));
    }

    private static Run run(ResultSet rs, int row) throws SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getObject("day", LocalDate.class),
                rs.getString("trigger"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                (Integer) rs.getObject("payments"),
                (Integer) rs.getObject("refunds"),
                (Integer) rs.getObject("found"),
                (Integer) rs.getObject("open"),
                rs.getString("failure"));
    }

    private static Mismatch mismatch(ResultSet rs, int row) throws SQLException {
        var closed = rs.getTimestamp("closed_at");
        return new Mismatch(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("subject"),
                rs.getString("payment_id"),
                rs.getObject("topup_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                (Long) rs.getObject("our_paise"),
                (Long) rs.getObject("their_paise"),
                rs.getString("our_status"),
                rs.getString("their_status"),
                rs.getString("detail"),
                rs.getString("state"),
                rs.getTimestamp("found_at").toInstant(),
                rs.getTimestamp("last_found_at").toInstant(),
                closed == null ? null : closed.toInstant(),
                rs.getString("closed_by"),
                rs.getString("close_reason"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
