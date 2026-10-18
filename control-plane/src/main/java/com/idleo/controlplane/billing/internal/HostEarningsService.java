package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.HostEarnings;
import com.idleo.controlplane.billing.SessionCharges;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** A host's earnings by session, summed from its {@code host_payable} ledger entries (ADR 0023). */
@Service
class HostEarningsService implements HostEarnings {

    private final JdbcClient jdbc;
    private final SessionCharges charges;
    private final OwnerEarningsQueries queries;
    private final Clock clock;

    HostEarningsService(JdbcClient jdbc, SessionCharges charges, OwnerEarningsQueries queries, Clock clock) {
        this.jdbc = jdbc;
        this.charges = charges;
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    public long earnedToday(UUID hostId) {
        var today = Instant.now(clock).atZone(OwnerEarningsController.IST).toLocalDate();
        return queries.between(
                List.of(hostId),
                today.atStartOfDay(OwnerEarningsController.IST).toInstant(),
                today.plusDays(1).atStartOfDay(OwnerEarningsController.IST).toInstant());
    }

    @Override
    public Map<UUID, Earned> bySession(UUID hostId, Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        var earned = new HashMap<UUID, Earned>();
        jdbc.sql("""
                        select ref_id,
                               coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credited,
                               coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as given_back
                        from billing.ledger_entries
                        where account = :account and owner_id = :hostId and ref_id in (:sessionIds)
                        group by ref_id
                        """)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("hostId", hostId)
                .param("sessionIds", sessionIds)
                .query(rs -> {
                    earned.put(
                            rs.getObject("ref_id", UUID.class),
                            new Earned(rs.getLong("credited"), rs.getLong("given_back")));
                });
        return earned;
    }

    @Override
    public long shareOf(long chargedPaise) {
        return SessionCharges.split(Paise.of(chargedPaise), charges.commissionPercent())
                .hostShare()
                .value();
    }
}
