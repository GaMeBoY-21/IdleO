package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.AuditTrail;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual adjustments (ADR 0055): a journal entry of two or more legs that sum to zero, each on an account an admin may
 * adjust, with a reason and a reference (a ticket or a reconciliation item). Every one is another admin's approval
 * (ADR 0051), and it posts only as approved, as one ledger transaction named by the approval, so running the approval
 * again posts nothing. A correction to a closed month is posted now, in an open one, and names the month it corrects.
 *
 * <p>The templates are fixed shapes of legs. A goodwill credit is free credit, so it is a grant ({@link CreditGrants},
 * with its caps and its lot), recorded here too; every other template, and a journal, is posted here. A reversal is an
 * adjustment too: the same legs the other way round, linked to what it reverses; a goodwill credit's is the grant's
 * reversal.
 */
@Component
class Adjustments {

    static final String ADJUSTMENT_REF = "adjustment";
    static final String REVERSAL_REF = "adjustment_reversal";

    /** A write-off is for small differences only: at most ₹100. Anything larger is a journal with its own reasons. */
    static final Paise WRITE_OFF_LIMIT = Paise.of(10_000);

    /**
     * The accounts an adjustment may post to. A renter's credit, and the GST paid ahead on it, live in lots that only
     * their own flows move (a goodwill credit goes through a grant). Refunds, frozen credit and payouts are each one
     * flow's, which the ledger's checks hold to their records.
     */
    static final Set<Account> ADJUSTABLE = EnumSet.of(
            Account.HOST_PAYABLE,
            Account.HOST_RECEIVABLE,
            Account.PLATFORM_REVENUE,
            Account.GATEWAY_CLEARING,
            Account.PROMOTIONS,
            Account.TAX_CGST,
            Account.TAX_SGST,
            Account.TAX_IGST,
            Account.FREE_CREDIT_EXPIRED,
            Account.CHARGEBACK_LOSSES,
            Account.TDS_PAYABLE,
            Account.EARNINGS_FORFEITED,
            Account.GATEWAY_FEES,
            Account.GST_INPUT_CREDIT,
            Account.WRITE_OFFS);

    enum Template {
        /** Any legs that balance, on the accounts above. */
        JOURNAL,
        /** Free credit to a renter: promotions to their wallet, as a grant. */
        GOODWILL_CREDIT,
        /** A fee we didn't expect: Razorpay kept it from what it settled. */
        FEE_CORRECTION,
        /** A small difference on what Razorpay holds for us, written off. */
        WRITE_OFF,
        /** The legs of another adjustment, the other way round. */
        REVERSAL;

        String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Template fromDb(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    record Leg(Account account, @Nullable UUID ownerId, Paise amount, Direction direction) {}

    /** What an admin asks for: the template, its legs, why, what it's about, and the closed month it corrects. */
    record Request(
            Template template,
            List<Leg> legs,
            String reason,
            String reference,
            @Nullable YearMonth correctsMonth) {

        Request {
            legs = List.copyOf(legs);
        }
    }

    record Adjustment(
            UUID id,
            Template template,
            String reason,
            String reference,
            @Nullable YearMonth correctsMonth,
            @Nullable UUID reversesId,
            UUID approvalId,
            UUID txnId,
            String requestedBy,
            Instant createdAt) {}

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final CreditGrants grants;
    private final Periods periods;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;

    Adjustments(
            JdbcClient jdbc,
            Ledger ledger,
            CreditGrants grants,
            Periods periods,
            PlatformTransactionManager transactionManager,
            AuditTrail audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.grants = grants;
        this.periods = periods;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Whether {@code request} can be posted as it stands: its legs balance and keep to the accounts and owners the
     * ledger allows, the template's shape holds, and the month it corrects is closed. Asked before the approval is
     * made, and again when it runs.
     *
     * @throws Refused saying what's wrong, in words for the admin
     */
    void check(Request request) {
        var legs = request.legs();
        if (legs.size() < 2) {
            throw new Refused("an adjustment needs at least two legs");
        }
        if (request.reason().strip().length() < 5 || request.reason().strip().length() > 500) {
            throw new Refused("the reason is 5 to 500 characters");
        }
        if (request.reference().isBlank() || request.reference().strip().length() > 200) {
            throw new Refused("the reference (a ticket or a reconciliation item) is 1 to 200 characters");
        }
        var debits = Paise.ZERO;
        var credits = Paise.ZERO;
        var seen = new HashSet<List<Object>>();
        for (var leg : legs) {
            if (!leg.amount().isPositive()) {
                throw new Refused("every leg is more than zero");
            }
            if (request.template() != Template.GOODWILL_CREDIT && !ADJUSTABLE.contains(leg.account())) {
                throw new Refused(leg.account().dbValue + " can't be adjusted: only its own flow moves it");
            }
            if (leg.account().owned != (leg.ownerId() != null)) {
                throw new Refused(leg.account().dbValue
                        + (leg.account().owned ? " needs the owner it belongs to" : " has no owner"));
            }
            if (!seen.add(java.util.Arrays.asList(leg.account(), leg.ownerId()))) {
                throw new Refused("an account and owner appears twice; put it in one leg");
            }
            if (leg.direction() == Direction.DEBIT) {
                debits = debits.plus(leg.amount());
            } else {
                credits = credits.plus(leg.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new Refused("the legs don't balance: debits " + debits.value() + " paise, credits " + credits.value()
                    + " paise");
        }
        switch (request.template()) {
            case GOODWILL_CREDIT -> shape(legs, Account.PROMOTIONS, Account.RENTER_WALLET, "promotions to a wallet");
            case FEE_CORRECTION -> shape(legs, Account.GATEWAY_FEES, Account.GATEWAY_CLEARING, "fees from clearing");
            case WRITE_OFF -> {
                shape(legs, Account.WRITE_OFFS, Account.GATEWAY_CLEARING, "write-offs from clearing");
                if (debits.isGreaterThan(WRITE_OFF_LIMIT)) {
                    throw new Refused("a write-off is at most " + WRITE_OFF_LIMIT.value() + " paise; use a journal");
                }
            }
            case REVERSAL -> throw new Refused("a reversal is asked for from the adjustment it reverses");
            case JOURNAL -> {}
        }
        if (request.correctsMonth() != null && !periods.closed(request.correctsMonth())) {
            throw new Refused(request.correctsMonth() + " isn't closed; a correction names a closed month");
        }
    }

    private static void shape(List<Leg> legs, Account debit, Account credit, String what) {
        boolean fits = legs.size() == 2
                && legs.stream().anyMatch(leg -> leg.account() == debit && leg.direction() == Direction.DEBIT)
                && legs.stream().anyMatch(leg -> leg.account() == credit && leg.direction() == Direction.CREDIT);
        if (!fits) {
            throw new Refused("this template is one debit and one credit: " + what);
        }
    }

    /** Posts an approved request, once: running the same approval again returns what it posted. */
    Adjustment post(UUID approvalId, Request request, AuditTrail.Actor actor) {
        var existing = byApproval(approvalId);
        if (existing.isPresent()) {
            return existing.get();
        }
        check(request);
        return Objects.requireNonNull(transactions.execute(status -> {
            var id = derived("adjustment", approvalId);
            UUID txnId;
            if (request.template() == Template.GOODWILL_CREDIT) {
                var credit = request.legs().stream()
                        .filter(leg -> leg.account() == Account.RENTER_WALLET)
                        .findFirst()
                        .orElseThrow();
                try {
                    txnId = grants.grant(
                                    Objects.requireNonNull(credit.ownerId()),
                                    credit.amount(),
                                    request.reason().strip(),
                                    "adjustment:" + approvalId,
                                    actor)
                            .grant()
                            .txnId();
                } catch (CreditGrants.Refused e) {
                    throw new Refused(e.getMessage());
                }
            } else {
                txnId = derived(ADJUSTMENT_REF, approvalId);
                ledger.post(new LedgerTransaction(txnId, ADJUSTMENT_REF, id, entries(request.legs())));
            }
            var adjustment = insert(id, request.template(), request, null, approvalId, txnId, actor);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "money.adjustment",
                    "adjustment",
                    id.toString(),
                    request.reason().strip(),
                    null,
                    Map.of("template", request.template().dbValue(), "txnId", txnId.toString())));
            return adjustment;
        }));
    }

    /**
     * Reverses an adjustment, once, as an approved adjustment of its own that names it: its legs the other way round,
     * or, for a goodwill credit, the grant's reversal, which takes back what's left of the credit.
     */
    Adjustment reverse(UUID approvalId, UUID adjustmentId, String reason, String reference, AuditTrail.Actor actor) {
        var existing = byApproval(approvalId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var original = find(adjustmentId).orElseThrow(() -> new Refused("there is no adjustment " + adjustmentId));
        if (original.template() == Template.REVERSAL) {
            throw new Refused("a reversal isn't reversed; make a new adjustment");
        }
        if (reversalOf(adjustmentId).isPresent()) {
            throw new Refused("adjustment " + adjustmentId + " is already reversed");
        }
        var request = new Request(Template.REVERSAL, List.of(), reason, reference, null);
        return Objects.requireNonNull(transactions.execute(status -> {
            var id = derived("adjustment", approvalId);
            UUID txnId;
            if (original.template() == Template.GOODWILL_CREDIT) {
                var grantId = jdbc.sql("select id from billing.credit_grants where txn_id = :txn")
                        .param("txn", original.txnId())
                        .query(UUID.class)
                        .single();
                try {
                    txnId = grants.reverse(grantId, reason.strip(), actor)
                            .reversal()
                            .txnId();
                } catch (CreditGrants.Refused e) {
                    throw new Refused(e.getMessage());
                }
            } else {
                var opposite = legs(original.txnId()).stream()
                        .map(leg -> new Leg(
                                leg.account(),
                                leg.ownerId(),
                                leg.amount(),
                                leg.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT))
                        .toList();
                txnId = derived(REVERSAL_REF, approvalId);
                ledger.post(new LedgerTransaction(txnId, REVERSAL_REF, id, entries(opposite)));
            }
            var reversal = insert(id, Template.REVERSAL, request, adjustmentId, approvalId, txnId, actor);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "money.adjustment.reverse",
                    "adjustment",
                    adjustmentId.toString(),
                    reason.strip(),
                    null,
                    Map.of("reversalId", id.toString(), "txnId", txnId.toString())));
            return reversal;
        }));
    }

    /** The legs of a posted transaction, as an adjustment's. */
    List<Leg> legs(UUID txnId) {
        return jdbc.sql("""
                        select account, owner_id, amount_paise, direction from billing.ledger_entries
                        where txn_id = :txn order by direction desc, account, owner_id
                        """)
                .param("txn", txnId)
                .query((rs, n) -> new Leg(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        Paise.of(rs.getLong("amount_paise")),
                        Direction.fromDb(rs.getString("direction"))))
                .list();
    }

    Optional<Adjustment> find(UUID id) {
        return jdbc.sql("select * from billing.adjustments where id = :id")
                .param("id", id)
                .query(Adjustments::adjustment)
                .optional();
    }

    Optional<Adjustment> reversalOf(UUID id) {
        return jdbc.sql("select * from billing.adjustments where reverses_id = :id")
                .param("id", id)
                .query(Adjustments::adjustment)
                .optional();
    }

    private Optional<Adjustment> byApproval(UUID approvalId) {
        return jdbc.sql("select * from billing.adjustments where approval_id = :approval")
                .param("approval", approvalId)
                .query(Adjustments::adjustment)
                .optional();
    }

    private Adjustment insert(
            UUID id,
            Template template,
            Request request,
            @Nullable UUID reversesId,
            UUID approvalId,
            UUID txnId,
            AuditTrail.Actor actor) {
        var now = clock.instant();
        jdbc.sql("""
                        insert into billing.adjustments
                            (id, template, reason, reference, corrects_month, reverses_id, approval_id, txn_id,
                             requested_by, created_at)
                        values (:id, :template, :reason, :reference, :month, :reverses, :approval, :txn, :by, :now)
                        """)
                .param("id", id)
                .param("template", template.dbValue())
                .param("reason", request.reason().strip())
                .param("reference", request.reference().strip())
                .param(
                        "month",
                        request.correctsMonth() == null
                                ? null
                                : request.correctsMonth().atDay(1))
                .param("reverses", reversesId)
                .param("approval", approvalId)
                .param("txn", txnId)
                .param("by", actor.label())
                .param("now", Timestamp.from(now))
                .update();
        return new Adjustment(
                id,
                template,
                request.reason().strip(),
                request.reference().strip(),
                request.correctsMonth(),
                reversesId,
                approvalId,
                txnId,
                actor.label(),
                now);
    }

    private static List<LedgerEntry> entries(List<Leg> legs) {
        var entries = new ArrayList<LedgerEntry>();
        for (var leg : legs) {
            entries.add(new LedgerEntry(leg.account(), leg.ownerId(), leg.amount(), leg.direction(), null));
        }
        return entries;
    }

    private static Adjustment adjustment(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var month = rs.getObject("corrects_month", LocalDate.class);
        return new Adjustment(
                rs.getObject("id", UUID.class),
                Template.fromDb(rs.getString("template")),
                rs.getString("reason"),
                rs.getString("reference"),
                month == null ? null : YearMonth.from(month),
                rs.getObject("reverses_id", UUID.class),
                rs.getObject("approval_id", UUID.class),
                rs.getObject("txn_id", UUID.class),
                rs.getString("requested_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static UUID derived(String kind, UUID cause) {
        return UUID.nameUUIDFromBytes((kind + ":" + cause).getBytes(StandardCharsets.UTF_8));
    }
}
