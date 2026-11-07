package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Settlement items that don't match the ledger (ADR 0055, ADR 0053): to be resolved within 3 days, by an approved
 * adjustment that names the item or as a known difference.
 */
@Component
class SettlementWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    SettlementWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "settlement";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.RECON_READ;
    }

    @Override
    public List<Item> open() {
        return jdbc.sql("select * from billing.settlement_items where state = 'open'")
                .query((rs, n) -> {
                    var id = rs.getString("id");
                    long theirs = rs.getLong("credit_paise") - rs.getLong("debit_paise");
                    var ours = rs.getObject("our_paise", Long.class);
                    return new Item(
                            id,
                            null,
                            null,
                            null,
                            "Settlement " + rs.getString("type") + " " + rs.getString("entity_id") + ": "
                                    + rs.getString("match").replace('_', ' '),
                            "/admin/settlements/?item=" + id,
                            "settlement",
                            rs.getTimestamp("found_at").toInstant(),
                            null,
                            false,
                            null,
                            null,
                            Math.abs((ours == null ? 0 : ours) - theirs),
                            false);
                })
                .list();
    }
}
