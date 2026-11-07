package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What Razorpay settles to our bank, against the ledger (ADR 0055). Every night, and when an admin asks, a day's
 * settlements (India's day) are fetched from Razorpay with every item they carried (the combined settlement
 * reconciliation report), kept by Razorpay's ids, so fetching a day again adds nothing. Each item is matched with ours:
 *
 * <ul>
 *   <li>a payment with the top-up it paid, the same amount, and a fee and tax no more than we expect ({@code
 *       billing.settlements.fee-basis-points}, and GST at {@code billing.settlements.fee-tax-basis-points} on it);
 *   <li>a refund with the refund part Razorpay processed, the same amount;
 *   <li>an adjustment (Razorpay's deduction for a chargeback) with the chargeback lost on that payment, the same amount;
 * </ul>
 *
 * and otherwise it's {@code missing_ours}, {@code amount} or {@code unexpected_fee}. A payment we credited that no
 * settlement carried {@code billing.settlements.missing-after} later is {@code missing_theirs}, until one does. A
 * settlement is matched when every item is, partly when some are, unmatched when none is or its items don't add up to
 * what it settled. A processed settlement is posted once: what it moved to the bank, Razorpay's fees ({@code
 * gateway_fees}) and their GST ({@code gst_input_credit}), out of {@code gateway_clearing}.
 *
 * <p>An item that doesn't match is resolved by an approved adjustment that names it, or as a known difference with a
 * reason; one open longer than {@code billing.settlements.stale-after} alerts, and every open one is an inbox item.
 */
@Component
class RazorpaySettlements {

    static final String SETTLEMENT_REF = "settlement";

    private static final Logger log = LoggerFactory.getLogger(RazorpaySettlements.class);

    private final RazorpayClient razorpay;
    private final RazorpayProperties settings;
    private final Ledger ledger;
    private final Adjustments adjustments;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final AuditTrail audit;
    private final Clock clock;
    private final long feeBasisPoints;
    private final long feeTaxBasisPoints;
    private final Duration missingAfter;
    private final Duration staleAfter;
    private final AtomicLong open = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();

    RazorpaySettlements(
            RazorpayClient razorpay,
            RazorpayProperties settings,
            Ledger ledger,
            Adjustments adjustments,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            AuditTrail audit,
            Clock clock,
            MeterRegistry registry,
            @Value("${billing.settlements.fee-basis-points}") long feeBasisPoints,
            @Value("${billing.settlements.fee-tax-basis-points}") long feeTaxBasisPoints,
            @Value("${billing.settlements.missing-after}") Duration missingAfter,
            @Value("${billing.settlements.stale-after}") Duration staleAfter) {
        this.razorpay = razorpay;
        this.settings = settings;
        this.ledger = ledger;
        this.adjustments = adjustments;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
        this.feeBasisPoints = feeBasisPoints;
        this.feeTaxBasisPoints = feeTaxBasisPoints;
        this.missingAfter = missingAfter;
        this.staleAfter = staleAfter;
        Gauge.builder("billing.settlements.items.open", open, AtomicLong::get)
                .description("Settlement items that don't match the ledger and aren't resolved")
                .register(registry);
        Gauge.builder("billing.settlements.items.stale", stale, AtomicLong::get)
                .description("Open settlement items older than billing.settlements.stale-after")
                .register(registry);
    }

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** What fetching a day found: its settlements, their items, and how many don't match. */
    record Fetched(LocalDate day, int settlements, int items, int open) {}

    /** Yesterday in India, once a day. Fetching a day twice adds nothing, so every instance may. */
    @Scheduled(cron = "${billing.settlements.cron}", zone = "Asia/Kolkata")
    void nightly() {
        if (!settings.enabled()) {
            return;
        }
        try {
            fetch(LocalDate.ofInstant(clock.instant(), Periods.INDIA).minusDays(1));
        } catch (RazorpayClient.Unreachable e) {
            // The next night, or an admin's fetch, tries again: nothing is half kept.
            log.warn("Razorpay's settlements couldn't be fetched: {}", e.getMessage());
        }
    }

    /** A day fetched now, for an admin, audited. */
    Fetched fetch(LocalDate day, AuditTrail.Actor actor, String reason) {
        if (!settings.enabled()) {
            throw new Refused("Razorpay isn't configured on this server");
        }
        if (day.isAfter(LocalDate.ofInstant(clock.instant(), Periods.INDIA))) {
            throw new Refused(day + " hasn't happened yet");
        }
        var fetched = fetch(day);
        audit.record(new AuditTrail.Entry(
                actor,
                "settlements.fetch",
                "settlement_day",
                day.toString(),
                reason,
                null,
                Map.of("settlements", fetched.settlements(), "open", fetched.open())));
        return fetched;
    }

    /** One day's settlements and their items, each matched, every processed one posted; then what's missing on theirs. */
    Fetched fetch(LocalDate day) {
        var from = day.atStartOfDay(Periods.INDIA).toInstant();
        var to = day.plusDays(1).atStartOfDay(Periods.INDIA).toInstant();
        var settlements = razorpay.settlements(from, to);
        var items = razorpay.settlementItems(day);
        int found = 0;
        for (var settlement : settlements) {
            var carried = items.stream()
                    .filter(item -> settlement.id().equals(item.settlementId()))
                    .toList();
            found += carried.size();
            transactions.executeWithoutResult(status -> keep(settlement, carried));
        }
        transactions.executeWithoutResult(status -> missingTheirs());
        count();
        return new Fetched(day, settlements.size(), found, Math.toIntExact(open.get()));
    }

    private void keep(RazorpayClient.Settlement settlement, List<RazorpayClient.SettlementItem> carried) {
        var now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into billing.settlements
                            (id, amount_paise, fees_paise, tax_paise, status, utr, settled_at, fetched_at, state, version)
                        values (:id, :amount, :fees, :tax, :status, :utr, :settledAt, :now, 'unmatched', 1)
                        on conflict (id) do update
                            set status = excluded.status, utr = excluded.utr, fetched_at = excluded.fetched_at,
                                version = billing.settlements.version + 1
                        """)
                .param("id", settlement.id())
                .param("amount", settlement.amountPaise())
                .param("fees", settlement.feesPaise())
                .param("tax", settlement.taxPaise())
                .param("status", settlement.status())
                .param("utr", settlement.utr())
                .param("settledAt", Timestamp.from(settlement.createdAt()))
                .param("now", now)
                .update();
        for (var item : carried) {
            var match = match(item);
            jdbc.sql("""
                            insert into billing.settlement_items
                                (id, settlement_id, entity_id, type, credit_paise, debit_paise, fee_paise, tax_paise,
                                 our_paise, match, state, found_at, version)
                            values (:id, :settlement, :entity, :type, :credit, :debit, :fee, :tax, :ours, :match,
                                    :state, :now, 1)
                            on conflict on constraint settlement_items_once do nothing
                            """)
                    .param("id", ids.newId())
                    .param("settlement", settlement.id())
                    .param("entity", item.entityId())
                    .param("type", type(item.type()))
                    .param("credit", item.creditPaise())
                    .param("debit", item.debitPaise())
                    .param("fee", item.feePaise())
                    .param("tax", item.taxPaise())
                    .param("ours", match.ours())
                    .param("match", match.kind())
                    .param("state", match.kind().equals("matched") ? "matched" : "open")
                    .param("now", now)
                    .update();
            if (item.type().equals("payment")) {
                // A payment that was missing on theirs is carried now.
                jdbc.sql("""
                                update billing.settlement_items
                                set state = 'resolved', resolution = 'settled_later', resolved_by = 'settlement',
                                    resolved_at = :now, version = version + 1
                                where settlement_id is null and type = 'payment' and entity_id = :entity
                                  and state = 'open'
                                """).param("entity", item.entityId()).param("now", now).update();
            }
        }
        state(settlement.id());
        if (settlement.status().equals("processed")) {
            post(settlement);
        }
    }

    /** Razorpay's item types, as kept: anything it adds later is kept as an adjustment, which never matches by itself. */
    private static String type(String type) {
        return switch (type) {
            case "payment", "refund", "adjustment", "transfer" -> type;
            default -> "adjustment";
        };
    }

    private record Match(String kind, @Nullable Long ours) {}

    private Match match(RazorpayClient.SettlementItem item) {
        return switch (item.type()) {
            case "payment" -> {
                var ours = jdbc.sql(
                                "select amount_paise from billing.topups where payment_id = :id and status = 'paid'")
                        .param("id", item.entityId())
                        .query(Long.class)
                        .optional();
                if (ours.isEmpty()) {
                    yield new Match("missing_ours", null);
                }
                if (ours.get() != item.creditPaise()) {
                    yield new Match("amount", ours.get());
                }
                yield new Match(feeExpected(item) ? "matched" : "unexpected_fee", ours.get());
            }
            case "refund" -> {
                var ours = jdbc.sql("""
                                select amount_paise from billing.refund_parts
                                where razorpay_refund_id = :id and status = 'processed'
                                """)
                        .param("id", item.entityId())
                        .query(Long.class)
                        .optional();
                if (ours.isEmpty()) {
                    yield new Match("missing_ours", null);
                }
                yield new Match(ours.get() == item.debitPaise() ? "matched" : "amount", ours.get());
            }
            case "adjustment" -> {
                var ours = item.paymentId() == null
                        ? Optional.<Long>empty()
                        : jdbc.sql("""
                                        select lost_paise from billing.chargebacks
                                        where payment_id = :payment and lost_paise > 0
                                        """)
                                .param("payment", item.paymentId())
                                .query(Long.class)
                                .optional();
                if (ours.isEmpty()) {
                    yield new Match("missing_ours", null);
                }
                yield new Match(ours.get() == item.debitPaise() ? "matched" : "amount", ours.get());
            }
            default -> new Match("missing_ours", null);
        };
    }

    /** Whether a payment's fee and its tax are no more than we expect, a paisa either way for rounding. */
    private boolean feeExpected(RazorpayClient.SettlementItem item) {
        long fee = Math.round(item.creditPaise() * feeBasisPoints / 10_000.0);
        long tax = Math.round(item.feePaise() * feeTaxBasisPoints / 10_000.0);
        return item.feePaise() <= fee + 1 && item.taxPaise() <= tax + 1;
    }

    /** A settlement's state from its items, and whether they add up to what it settled. */
    private void state(String settlementId) {
        var sums = jdbc.sql("""
                        select s.amount_paise,
                               coalesce(sum(i.credit_paise - i.debit_paise - i.fee_paise - i.tax_paise), 0) as carried,
                               count(i.id) as items,
                               count(i.id) filter (where i.state in ('matched', 'resolved')) as agreed
                        from billing.settlements s left join billing.settlement_items i on i.settlement_id = s.id
                        where s.id = :id
                        group by s.amount_paise
                        """)
                .param("id", settlementId)
                .query((rs, n) -> new long[] {
                    rs.getLong("amount_paise"), rs.getLong("carried"), rs.getLong("items"), rs.getLong("agreed")
                })
                .single();
        String state;
        if (sums[2] == 0 || sums[0] != sums[1] || sums[3] == 0) {
            state = "unmatched";
        } else {
            state = sums[3] == sums[2] ? "matched" : "partly";
        }
        jdbc.sql("update billing.settlements set state = :state where id = :id and state <> :state")
                .param("state", state)
                .param("id", settlementId)
                .update();
    }

    /** What a processed settlement moved: the bank and Razorpay's fees and their tax, out of clearing. Once. */
    private void post(RazorpayClient.Settlement settlement) {
        long total = settlement.amountPaise() + settlement.feesPaise() + settlement.taxPaise();
        if (total == 0) {
            return;
        }
        var txnId = UUID.nameUUIDFromBytes((SETTLEMENT_REF + ":" + settlement.id()).getBytes(StandardCharsets.UTF_8));
        var entries = new ArrayList<LedgerEntry>();
        if (settlement.amountPaise() > 0) {
            entries.add(LedgerEntry.debit(Account.BANK, null, Paise.of(settlement.amountPaise())));
        }
        if (settlement.feesPaise() > 0) {
            entries.add(LedgerEntry.debit(Account.GATEWAY_FEES, null, Paise.of(settlement.feesPaise())));
        }
        if (settlement.taxPaise() > 0) {
            entries.add(LedgerEntry.debit(Account.GST_INPUT_CREDIT, null, Paise.of(settlement.taxPaise())));
        }
        entries.add(LedgerEntry.credit(Account.GATEWAY_CLEARING, null, Paise.of(total)));
        var refId = UUID.nameUUIDFromBytes(settlement.id().getBytes(StandardCharsets.UTF_8));
        ledger.post(new LedgerTransaction(txnId, SETTLEMENT_REF, refId, entries));
        jdbc.sql("update billing.settlements set txn_id = :txn where id = :id and txn_id is null")
                .param("txn", txnId)
                .param("id", settlement.id())
                .update();
    }

    /** Payments we credited more than missing-after ago, in the month before, that no settlement has carried. */
    private void missingTheirs() {
        var now = clock.instant();
        var before = now.minus(missingAfter);
        var missing = jdbc.sql("""
                        select t.payment_id, t.amount_paise from billing.topups t
                        where t.status = 'paid' and t.payment_id is not null
                          and t.paid_at < :before and t.paid_at >= :since
                          and not exists (select 1 from billing.settlement_items i
                                          where i.type = 'payment' and i.entity_id = t.payment_id)
                        """)
                .param("before", Timestamp.from(before))
                .param("since", Timestamp.from(before.minus(Duration.ofDays(31))))
                .query((rs, n) -> Map.entry(rs.getString("payment_id"), rs.getLong("amount_paise")))
                .list();
        for (var payment : missing) {
            jdbc.sql("""
                            insert into billing.settlement_items
                                (id, settlement_id, entity_id, type, credit_paise, debit_paise, fee_paise, tax_paise,
                                 our_paise, match, state, found_at, version)
                            values (:id, null, :entity, 'payment', 0, 0, 0, 0, :ours, 'missing_theirs', 'open', :now, 1)
                            on conflict on constraint settlement_items_once do nothing
                            """)
                    .param("id", ids.newId())
                    .param("entity", payment.getKey())
                    .param("ours", payment.getValue())
                    .param("now", Timestamp.from(now))
                    .update();
        }
    }

    /** Resolves an open item as a known difference, with why, audited. */
    void known(UUID itemId, String reason, AuditTrail.Actor actor) {
        resolve(itemId, "known_difference", null, reason, actor);
    }

    /** Resolves an open item by the approved adjustment that put it right, which must name it in its reference. */
    void adjusted(UUID itemId, UUID adjustmentId, String reason, AuditTrail.Actor actor) {
        var adjustment = adjustments
                .find(adjustmentId)
                .orElseThrow(() -> new Refused("there's no posted adjustment " + adjustmentId));
        if (!adjustment.reference().contains(itemId.toString())) {
            throw new Refused("adjustment " + adjustmentId + " doesn't name this item in its reference");
        }
        resolve(itemId, "adjustment", adjustmentId, reason, actor);
    }

    private void resolve(
            UUID itemId, String resolution, @Nullable UUID adjustmentId, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            var item = jdbc.sql("select state, settlement_id from billing.settlement_items where id = :id for update")
                    .param("id", itemId)
                    .query((rs, n) -> new String[] {rs.getString("state"), rs.getString("settlement_id")})
                    .optional()
                    .orElseThrow(() -> new Refused("there's no settlement item " + itemId));
            if (!"open".equals(item[0])) {
                throw new Refused("the item isn't open");
            }
            jdbc.sql("""
                            update billing.settlement_items
                            set state = 'resolved', resolution = :resolution, adjustment_id = :adjustment,
                                reason = :reason, resolved_by = :by, resolved_at = :now, version = version + 1
                            where id = :id
                            """)
                    .param("resolution", resolution)
                    .param("adjustment", adjustmentId)
                    .param("reason", reason.strip())
                    .param("by", actor.label())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", itemId)
                    .update();
            if (item[1] != null) {
                state(item[1]);
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "settlement_item.resolve",
                    "settlement_item",
                    itemId.toString(),
                    reason.strip(),
                    null,
                    adjustmentId == null
                            ? Map.of("resolution", resolution)
                            : Map.of("resolution", resolution, "adjustmentId", adjustmentId.toString())));
        });
        count();
    }

    /** Keeps the open and stale counts right on every instance. */
    @Scheduled(fixedDelayString = "${billing.settlements.count-every}")
    void count() {
        var counts = jdbc.sql("""
                        select count(*) as open, count(*) filter (where found_at < :stale) as stale
                        from billing.settlement_items where state = 'open'
                        """)
                .param("stale", Timestamp.from(clock.instant().minus(staleAfter)))
                .query((rs, n) -> new long[] {rs.getLong("open"), rs.getLong("stale")})
                .single();
        open.set(counts[0]);
        stale.set(counts[1]);
    }

    /** Whether the items of settlements made in a month, and those missing on theirs found in it, are all resolved. */
    long openIn(Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from billing.settlement_items i
                        left join billing.settlements s on s.id = i.settlement_id
                        where i.state = 'open'
                          and ((s.settled_at >= :from and s.settled_at < :to)
                               or (i.settlement_id is null and i.found_at >= :from and i.found_at < :to))
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .single();
    }
}
