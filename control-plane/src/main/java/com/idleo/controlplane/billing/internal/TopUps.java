package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.identity.UserDirectory;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Buying credit (ADR 0039). A top-up is a Razorpay order for its amount; its credit is posted only once the server has
 * proof of capture: Checkout's signature checked against the order id we stored, and then the payment fetched from
 * Razorpay's API and found {@code captured}, for this order, this amount, in INR. The browser's handler and the
 * webhooks ({@code payment.captured}, {@code order.paid}) both come here, and whichever arrives first credits: the
 * posting's txn_id is named by the payment, and the top-up's row is locked while it's decided, so the other changes
 * nothing. Never the browser alone. An authorised payment that isn't captured is never credited (auto-capture is on;
 * the reconciliation reports one that stays so).
 *
 * <p>The credit is one balanced transaction: {@code gateway_clearing} (what Razorpay holds for us) to a bought lot of
 * the renter's own, which never expires.
 */
@Service
class TopUps {

    static final String TOPUP_REF = "topup";

    private static final Logger log = LoggerFactory.getLogger(TopUps.class);

    private final RazorpayClient razorpay;
    private final RazorpayProperties settings;
    private final Ledger ledger;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final UserDirectory users;
    private final LegalSettings legal;
    private final PaymentMetrics metrics;
    private final Duration abandonAfter;

    TopUps(
            RazorpayClient razorpay,
            RazorpayProperties settings,
            Ledger ledger,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            UserDirectory users,
            LegalSettings legal,
            PaymentMetrics metrics,
            @Value("${billing.top-ups.abandon-after}") Duration abandonAfter) {
        this.razorpay = razorpay;
        this.settings = settings;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.users = users;
        this.legal = legal;
        this.metrics = metrics;
        this.abandonAfter = abandonAfter;
    }

    enum Status {
        PENDING,
        PAID,
        FAILED,
        ABANDONED;

        String dbValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Status fromDb(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    record TopUp(
            UUID id,
            UUID userId,
            Paise amount,
            String receipt,
            String orderId,
            Status status,
            @Nullable String paymentId,
            @Nullable String method,
            @Nullable String cardNetwork,
            @Nullable String cardLast4,
            @Nullable String failureReason,
            @Nullable String creditedBy,
            @Nullable UUID txnId,
            Instant createdAt,
            @Nullable Instant paidAt) {}

    /** What happened to a payment brought to us. */
    enum Outcome {
        /** The credit was posted now. */
        CREDITED,
        /** It had been credited already: nothing changed. */
        ALREADY_CREDITED,
        /** Razorpay hasn't captured it yet: nothing is credited until it does. */
        NOT_CAPTURED,
        /** The payment failed. */
        FAILED
    }

    /**
     * Orders a top-up of {@code amount} for {@code userId}, within the limits: Razorpay's order first (its receipt is
     * ours, and Razorpay refuses the same receipt twice), then our row, pending.
     */
    TopUp order(UUID userId, Paise amount) {
        if (!settings.enabled()) {
            throw ApiProblem.of(Problem.PAYMENTS_UNAVAILABLE, "Payments aren't set up on this server.");
        }
        checkLimits(userId, amount);
        var id = ids.newId();
        var receipt = "tu_" + id.toString().replace("-", "");
        RazorpayClient.Order order;
        try {
            order = razorpay.createOrder(amount.value(), receipt, Map.of("topup_id", id.toString()));
        } catch (RazorpayClient.Refused | RazorpayClient.Unreachable e) {
            log.warn("Razorpay didn't create the order for top-up {}: {}", id, e.getMessage());
            throw ApiProblem.of(Problem.PAYMENTS_UNAVAILABLE, "Razorpay couldn't start the payment. Try again soon.");
        }
        if (order.amount() != amount.value() || !"INR".equals(order.currency())) {
            throw new IllegalStateException("Razorpay's order " + order.id() + " isn't for " + amount.value() + " INR");
        }
        var now = now();
        jdbc.sql("""
                        insert into billing.topups
                            (id, user_id, amount_paise, currency, receipt, order_id, status, created_at, updated_at)
                        values (:id, :userId, :amount, 'INR', :receipt, :orderId, 'pending', :now, :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("amount", amount.value())
                .param("receipt", receipt)
                .param("orderId", order.id())
                .param("now", Timestamp.from(now))
                .update();
        metrics.started();
        return find(id).orElseThrow();
    }

    private void checkLimits(UUID userId, Paise amount) {
        var limits = legal.topUps();
        var created = users.createdAt(userId).orElse(Instant.EPOCH);
        boolean young = created.isAfter(clock.instant().minus(limits.newAccountAge()));
        long max = young ? limits.newAccountMaxPaise() : limits.maxPaise();
        long perDay = young ? limits.newAccountPerDayPaise() : limits.perDayPaise();
        if (amount.value() < limits.minPaise()) {
            throw outside("The smallest top-up is " + Rupees.format(limits.minPaise()) + ".");
        }
        if (amount.value() > max) {
            throw outside("The largest top-up is " + Rupees.format(max)
                    + (young
                            ? " while an account is less than "
                                    + limits.newAccountAge().toDays() + " days old."
                            : "."));
        }
        long today = jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) from billing.topups
                        where user_id = :userId and status = 'paid' and paid_at > :since
                        """)
                .param("userId", userId)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(1))))
                .query(Long.class)
                .single();
        if (today + amount.value() > perDay) {
            throw outside("Top-ups are at most " + Rupees.format(perDay) + " in a day; you can add "
                    + Rupees.format(Math.max(0, perDay - today)) + " more today.");
        }
        long bought = jdbc.sql("""
                        select coalesce(sum(remaining_paise), 0) from billing.credit_lots
                        where user_id = :userId and kind = 'purchased'
                        """).param("userId", userId).query(Long.class).single();
        if (bought + amount.value() > limits.maxBoughtBalancePaise()) {
            throw outside("You can hold at most " + Rupees.format(limits.maxBoughtBalancePaise())
                    + " of bought credit; you can add "
                    + Rupees.format(Math.max(0, limits.maxBoughtBalancePaise() - bought))
                    + " now.");
        }
        var failures = jdbc.sql("""
                        select failed_at from billing.topup_failures
                        where user_id = :userId and failed_at > :since order by failed_at desc
                        """)
                .param("userId", userId)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .list();
        if (failures.size() > 5) {
            var wait = Duration.between(clock.instant(), failures.getFirst().plus(Duration.ofHours(1)));
            throw ApiProblem.of(
                            Problem.TOO_MANY_FAILED_PAYMENTS,
                            failures.size() + " payments failed in the last hour, so top-ups are paused for an hour.")
                    .retryAfter(wait.isNegative() ? Duration.ofSeconds(1) : wait);
        }
    }

    private static ApiProblem outside(String detail) {
        return ApiProblem.of(Problem.TOP_UP_OUT_OF_LIMITS, detail);
    }

    /**
     * Checkout's handler brought a payment: its signature must be Razorpay's for the order we stored, and then the
     * payment itself must be captured.
     */
    Outcome confirm(TopUp topUp, String paymentId, @Nullable String signature) {
        if (topUp.status() == Status.PAID) {
            return Outcome.ALREADY_CREDITED;
        }
        if (!RazorpaySignatures.paymentSignatureValid(
                topUp.orderId(), paymentId, signature, Objects.requireNonNull(settings.keySecret()))) {
            metrics.signatureFailed("checkout");
            throw ApiProblem.of(
                    Problem.PAYMENT_NOT_VERIFIED, "The payment's signature isn't Razorpay's for this top-up.");
        }
        return credit(topUp, paymentId, "checkout");
    }

    /**
     * The one way a top-up is credited: the payment fetched from Razorpay, then, under the top-up's row lock, checked
     * and posted in one transaction. A payment that isn't this top-up's, or not for its amount in INR, is refused and
     * credits nothing.
     */
    Outcome credit(TopUp topUp, String paymentId, String by) {
        RazorpayClient.Payment payment;
        try {
            payment = razorpay.fetchPayment(paymentId);
        } catch (RazorpayClient.Refused e) {
            throw ApiProblem.of(Problem.PAYMENT_NOT_VERIFIED, "Razorpay doesn't know payment " + paymentId + ".");
        } catch (RazorpayClient.Unreachable e) {
            throw ApiProblem.of(Problem.PAYMENTS_UNAVAILABLE, "Razorpay can't be reached to check the payment.");
        }
        return credit(topUp, payment, by);
    }

    /** The same, for a payment already fetched (the reconciliation lists them). */
    Outcome credit(TopUp topUp, RazorpayClient.Payment payment, String by) {
        if (!topUp.orderId().equals(payment.orderId())
                || payment.amount() != topUp.amount().value()
                || !"INR".equals(payment.currency())) {
            log.error(
                    "Payment {} isn't top-up {}'s: order {}, {} {} (expected order {}, {} INR)",
                    payment.id(),
                    topUp.id(),
                    payment.orderId(),
                    payment.amount(),
                    payment.currency(),
                    topUp.orderId(),
                    topUp.amount().value());
            metrics.mismatched();
            throw ApiProblem.of(
                    Problem.PAYMENT_NOT_VERIFIED, "Razorpay's payment isn't for this top-up's order and amount.");
        }
        if ("failed".equals(payment.status())) {
            failed(topUp, payment.id(), payment.method(), payment.errorDescription());
            return Outcome.FAILED;
        }
        if (!"captured".equals(payment.status()) || !payment.captured()) {
            return Outcome.NOT_CAPTURED;
        }
        var credited = Objects.requireNonNull(transactions.execute(status -> {
            var locked = lock(topUp.id());
            if (locked.status() == Status.PAID) {
                return false;
            }
            ledger.lockWallet(locked.userId());
            var lot = ledger.openLot(
                    locked.userId(), CreditLot.Kind.PURCHASED, TOPUP_REF, locked.id(), locked.amount(), null);
            var txnId = txnId(payment.id());
            ledger.post(new LedgerTransaction(
                    txnId,
                    TOPUP_REF,
                    locked.id(),
                    List.of(
                            LedgerEntry.debit(Account.GATEWAY_CLEARING, null, locked.amount()),
                            LedgerEntry.creditLot(
                                    Account.RENTER_PURCHASED, locked.userId(), locked.amount(), lot.id()))));
            jdbc.sql("""
                            update billing.topups
                            set status = 'paid', payment_id = :paymentId, method = :method, card_network = :network,
                                card_last4 = :last4, failure_reason = null, credited_by = :by, txn_id = :txnId,
                                paid_at = :now, updated_at = :now
                            where id = :id
                            """)
                    .param("paymentId", payment.id())
                    .param("method", payment.method())
                    .param("network", payment.cardNetwork())
                    .param("last4", payment.cardLast4())
                    .param("by", by)
                    .param("txnId", txnId)
                    .param("now", Timestamp.from(now()))
                    .param("id", locked.id())
                    .update();
            metrics.paid(payment.method(), Duration.between(payment.createdAt(), clock.instant()));
            return true;
        }));
        if (credited) {
            log.info(
                    "Top-up {} credited ({} paise, by {})",
                    topUp.id(),
                    topUp.amount().value(),
                    by);
        }
        return credited ? Outcome.CREDITED : Outcome.ALREADY_CREDITED;
    }

    /**
     * A payment on the top-up's order failed: the top-up says so, in Razorpay's words, unless it's been paid (a later
     * attempt on the same order can still succeed, and paid is final). Counted for the velocity rule once a payment.
     */
    void failed(TopUp topUp, String paymentId, @Nullable String method, @Nullable String reason) {
        transactions.executeWithoutResult(status -> {
            var locked = lock(topUp.id());
            int counted = jdbc.sql("""
                            insert into billing.topup_failures (payment_id, user_id, topup_id, failed_at)
                            values (:paymentId, :userId, :topupId, :now)
                            on conflict (payment_id) do nothing
                            """)
                    .param("paymentId", paymentId)
                    .param("userId", locked.userId())
                    .param("topupId", locked.id())
                    .param("now", Timestamp.from(now()))
                    .update();
            if (locked.status() != Status.PAID) {
                jdbc.sql("""
                                update billing.topups
                                set status = 'failed', failure_reason = :reason, method = coalesce(:method, method),
                                    updated_at = :now
                                where id = :id
                                """)
                        .param("reason", reason == null || reason.isBlank() ? "The payment didn't go through." : reason)
                        .param("method", method)
                        .param("now", Timestamp.from(now()))
                        .param("id", locked.id())
                        .update();
            }
            if (counted > 0) {
                metrics.failed(method);
            }
        });
    }

    /** Orders still pending a day on are abandoned; a payment captured later is still credited. */
    @Scheduled(fixedDelayString = "${billing.top-ups.abandon-sweep-every}")
    int abandonStale() {
        return jdbc.sql("""
                        update billing.topups set status = 'abandoned', updated_at = :now
                        where status = 'pending' and created_at < :before
                        """)
                .param("now", Timestamp.from(now()))
                .param("before", Timestamp.from(clock.instant().minus(abandonAfter)))
                .update();
    }

    /** The ledger transaction that credits a payment: named by the payment, so it posts once whoever brings it. */
    static UUID txnId(String paymentId) {
        return UUID.nameUUIDFromBytes(("topup-payment:" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    Optional<TopUp> find(UUID id) {
        return jdbc.sql("select * from billing.topups where id = :id")
                .param("id", id)
                .query(TopUps::topUp)
                .optional();
    }

    Optional<TopUp> byOrder(String orderId) {
        return jdbc.sql("select * from billing.topups where order_id = :orderId")
                .param("orderId", orderId)
                .query(TopUps::topUp)
                .optional();
    }

    List<TopUp> ofUser(UUID userId, int limit) {
        return jdbc.sql(
                        "select * from billing.topups where user_id = :userId order by created_at desc, id desc limit :limit")
                .param("userId", userId)
                .param("limit", limit)
                .query(TopUps::topUp)
                .list();
    }

    private TopUp lock(UUID id) {
        return jdbc.sql("select * from billing.topups where id = :id for update")
                .param("id", id)
                .query(TopUps::topUp)
                .single();
    }

    static TopUp topUp(ResultSet rs, int row) throws SQLException {
        var paid = rs.getTimestamp("paid_at");
        return new TopUp(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Paise.of(rs.getLong("amount_paise")),
                rs.getString("receipt"),
                rs.getString("order_id"),
                Status.fromDb(rs.getString("status")),
                rs.getString("payment_id"),
                rs.getString("method"),
                rs.getString("card_network"),
                rs.getString("card_last4"),
                rs.getString("failure_reason"),
                rs.getString("credited_by"),
                rs.getObject("txn_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                paid == null ? null : paid.toInstant());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
