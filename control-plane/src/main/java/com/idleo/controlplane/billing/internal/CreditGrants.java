package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.credit;
import static com.idleo.controlplane.billing.internal.LedgerEntry.debit;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.UserDirectory;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Free credit (ADR 0014): a grant debits {@code promotions} and credits the renter's wallet; a
 * reversal posts the opposite. Each writes an append-only audit row in the same database transaction
 * as its ledger posting, and each is safe to re-run: a grant by its idempotency key, a reversal by the
 * grant it reverses.
 */
@Service
@EnableConfigurationProperties(CreditProperties.class)
class CreditGrants {

    static final String GRANT_REF = "credit_grant";
    static final String REVERSAL_REF = "credit_grant_reversal";

    private final Ledger ledger;
    private final UserDirectory users;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final CreditProperties limits;

    CreditGrants(
            Ledger ledger,
            UserDirectory users,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            CreditProperties limits) {
        this.ledger = ledger;
        this.users = users;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.limits = limits;
    }

    record Grant(
            UUID id,
            UUID userId,
            Paise amount,
            String reason,
            String grantedBy,
            String idempotencyKey,
            UUID txnId,
            Instant createdAt) {}

    record Reversal(UUID id, UUID grantId, String reason, String reversedBy, UUID txnId, Instant createdAt) {}

    /** A grant, and whether this call made it ({@code false}: the key had already been granted). */
    record GrantOutcome(Grant grant, boolean created, Paise walletBalance) {}

    /** A reversal, and whether this call made it ({@code false}: the grant was already reversed). */
    record ReversalOutcome(Reversal reversal, Grant grant, boolean created, Paise walletBalance) {}

    /** The request was refused; nothing changed. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * Grants free credit. The same key again, for the same user and amount, changes nothing and returns
     * the original grant; for anything else it's refused.
     */
    GrantOutcome grant(UUID userId, Paise amount, String reason, String idempotencyKey, String operator) {
        requireText(reason, "a reason");
        requireText(idempotencyKey, "an idempotency key");
        requireText(operator, "the operator");
        if (!amount.isPositive()) {
            throw new Refused("the amount must be above zero");
        }
        if (amount.isGreaterThan(Paise.of(limits.maxGrantPaise()))) {
            throw new Refused(
                    "one grant may give at most " + limits.maxGrantPaise() + " paise; this asks for " + amount.value());
        }
        if (!users.exists(userId)) {
            throw new Refused("there is no user " + userId);
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var txnId = derivedId(GRANT_REF, idempotencyKey);
            var grant = new Grant(
                    ids.newId(),
                    userId,
                    amount,
                    reason.strip(),
                    operator.strip(),
                    idempotencyKey,
                    txnId,
                    Instant.now(clock));
            int inserted = jdbc.sql("""
                            insert into billing.credit_grants
                                (id, user_id, amount_paise, reason, granted_by, idempotency_key, txn_id, created_at)
                            values (:id, :userId, :amount, :reason, :grantedBy, :key, :txnId, :createdAt)
                            on conflict (idempotency_key) do nothing
                            """)
                    .param("id", grant.id())
                    .param("userId", userId)
                    .param("amount", amount.value())
                    .param("reason", grant.reason())
                    .param("grantedBy", grant.grantedBy())
                    .param("key", idempotencyKey)
                    .param("txnId", txnId)
                    .param("createdAt", Timestamp.from(grant.createdAt()))
                    .update();
            if (inserted == 0) {
                var existing = findGrantByKey(idempotencyKey).orElseThrow();
                if (!existing.userId().equals(userId) || !existing.amount().equals(amount)) {
                    throw new Refused("the key " + idempotencyKey + " was already used for grant " + existing.id()
                            + " of " + existing.amount().value() + " paise to user " + existing.userId());
                }
                return new GrantOutcome(
                        existing, false, ledger.lockWallet(userId).balance());
            }
            var wallet = ledger.lockWallet(userId);
            var after = wallet.balance().plus(amount);
            if (after.isGreaterThan(Paise.of(limits.maxWalletPaise()))) {
                throw new Refused("the grant would take the wallet to " + after.value() + " paise, above the cap of "
                        + limits.maxWalletPaise());
            }
            ledger.post(new LedgerTransaction(
                    txnId,
                    GRANT_REF,
                    grant.id(),
                    List.of(debit(Account.PROMOTIONS, null, amount), credit(Account.RENTER_WALLET, userId, amount))));
            return new GrantOutcome(grant, true, after);
        }));
    }

    /**
     * Reverses a grant with a new, opposite transaction; the grant itself never changes. Reversing it
     * again changes nothing. Refused if the wallet's available balance can't cover it: credit already
     * spent or reserved can't be taken back.
     */
    ReversalOutcome reverse(UUID grantId, String reason, String operator) {
        requireText(reason, "a reason");
        requireText(operator, "the operator");
        return Objects.requireNonNull(transactions.execute(status -> {
            var grant = findGrant(grantId).orElseThrow(() -> new Refused("there is no grant " + grantId));
            var reversal = new Reversal(
                    ids.newId(),
                    grantId,
                    reason.strip(),
                    operator.strip(),
                    derivedId(REVERSAL_REF, grantId.toString()),
                    Instant.now(clock));
            int inserted = jdbc.sql("""
                            insert into billing.credit_grant_reversals
                                (id, grant_id, reason, reversed_by, txn_id, created_at)
                            values (:id, :grantId, :reason, :reversedBy, :txnId, :createdAt)
                            on conflict (grant_id) do nothing
                            """)
                    .param("id", reversal.id())
                    .param("grantId", grantId)
                    .param("reason", reversal.reason())
                    .param("reversedBy", reversal.reversedBy())
                    .param("txnId", reversal.txnId())
                    .param("createdAt", Timestamp.from(reversal.createdAt()))
                    .update();
            if (inserted == 0) {
                var existing = findReversal(grantId).orElseThrow();
                return new ReversalOutcome(
                        existing,
                        grant,
                        false,
                        ledger.lockWallet(grant.userId()).balance());
            }
            var wallet = ledger.lockWallet(grant.userId());
            if (grant.amount().isGreaterThan(wallet.available())) {
                throw new Refused("the wallet has " + wallet.available().value() + " paise available, less than the "
                        + grant.amount().value() + " paise grant: credit already spent or reserved can't be reversed");
            }
            ledger.post(new LedgerTransaction(
                    reversal.txnId(),
                    REVERSAL_REF,
                    reversal.id(),
                    List.of(
                            debit(Account.RENTER_WALLET, grant.userId(), grant.amount()),
                            credit(Account.PROMOTIONS, null, grant.amount()))));
            return new ReversalOutcome(reversal, grant, true, wallet.balance().minus(grant.amount()));
        }));
    }

    /** A transaction id derived from what caused it, so the same cause always posts the same txn_id. */
    private static UUID derivedId(String kind, String cause) {
        return UUID.nameUUIDFromBytes((kind + ":" + cause).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new Refused(what + " is required");
        }
    }

    private Optional<Grant> findGrantByKey(String idempotencyKey) {
        return jdbc.sql("select * from billing.credit_grants where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(CreditGrants::grant)
                .optional();
    }

    private Optional<Grant> findGrant(UUID grantId) {
        return jdbc.sql("select * from billing.credit_grants where id = :id")
                .param("id", grantId)
                .query(CreditGrants::grant)
                .optional();
    }

    private Optional<Reversal> findReversal(UUID grantId) {
        return jdbc.sql("select * from billing.credit_grant_reversals where grant_id = :grantId")
                .param("grantId", grantId)
                .query((rs, n) -> new Reversal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("grant_id", UUID.class),
                        rs.getString("reason"),
                        rs.getString("reversed_by"),
                        rs.getObject("txn_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private static Grant grant(ResultSet rs, int row) throws SQLException {
        return new Grant(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Paise.of(rs.getLong("amount_paise")),
                rs.getString("reason"),
                rs.getString("granted_by"),
                rs.getString("idempotency_key"),
                rs.getObject("txn_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
