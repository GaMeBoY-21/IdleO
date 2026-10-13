package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.DashboardFigures;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The billing module's figure for the admin dashboard (ADR 0027): what the latest wallet reconciliation found wrong,
 * an unbalanced ledger included; zero before the first run.
 */
@Component
class ReconciliationFigures implements DashboardFigures {

    private final JdbcClient jdbc;

    ReconciliationFigures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Figure, Long> figures() {
        long mismatches = jdbc.sql("""
                        select mismatches from billing.reconciliation_runs order by finished_at desc, id desc limit 1
                        """).query(Long.class).optional().orElse(0L);
        return Map.of(Figure.RECONCILIATION_MISMATCHES, mismatches);
    }
}
