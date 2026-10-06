package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code billing.reservations} (a test checks). The wallet's {@code reserved_paise}
 * moves with every hold, through {@link Ledger}, in the same transaction, under the wallet's lock.
 */
@Service
class CreditReservationService implements CreditReservations {

    private final Ledger ledger;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    CreditReservationService(Ledger ledger, JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID userId, UUID sessionId, Paise amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a hold must be above zero");
        }
        var wallet = ledger.lockWallet(userId);
        if (held(sessionId).isPresent()) {
            return true;
        }
        if (amount.isGreaterThan(wallet.available())) {
            return false;
        }
        jdbc.sql("""
                        insert into billing.reservations (id, user_id, session_id, amount_paise, status, created_at)
                        values (:id, :userId, :sessionId, :amount, 'active', :now)
                        """)
                .param("id", ids.newId())
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("amount", amount.value())
                .param("now", Timestamp.from(now()))
                .update();
        ledger.adjustReserved(userId, amount);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(UUID sessionId) {
        record Hold(UUID id, UUID userId, long amount) {}
        var hold = jdbc.sql("""
                        select id, user_id, amount_paise from billing.reservations
                        where session_id = :sessionId and status = 'active'
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Hold(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getLong("amount_paise")))
                .optional();
        if (hold.isEmpty()) {
            return false;
        }
        // The wallet first, as every posting does, so a release and a hold can't deadlock.
        ledger.lockWallet(hold.get().userId());
        int released = jdbc.sql("""
                        update billing.reservations set status = 'released', released_at = :now
                        where id = :id and status = 'active'
                        """)
                .param("now", Timestamp.from(now()))
                .param("id", hold.get().id())
                .update();
        if (released == 0) {
            return false;
        }
        ledger.adjustReserved(hold.get().userId(), Paise.of(hold.get().amount()).negate());
        return true;
    }

    @Override
    public Paise available(UUID userId) {
        return jdbc.sql("select balance_paise - reserved_paise from billing.wallets where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .map(Paise::of)
                .orElse(Paise.ZERO);
    }

    @Override
    public Optional<Paise> held(UUID sessionId) {
        return jdbc.sql(
                        "select amount_paise from billing.reservations where session_id = :sessionId and status = 'active'")
                .param("sessionId", sessionId)
                .query(Long.class)
                .optional()
                .map(Paise::of);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
