package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Chargebacks not settled yet (ADR 0053): timed by Razorpay's own deadline for our evidence, never by ours. */
@Component
class ChargebackWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    ChargebackWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "chargeback";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.PAYMENTS_READ;
    }

    @Override
    public List<Item> open() {
        return jdbc.sql("select * from billing.chargebacks where settled_at is null")
                .query((rs, n) -> {
                    var id = rs.getObject("id", UUID.class);
                    var due = rs.getTimestamp("respond_by");
                    return new Item(
                            id.toString(),
                            rs.getObject("topup_id") == null ? null : "payment",
                            rs.getObject("topup_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            "Chargeback " + rs.getString("dispute_id"),
                            "/admin/chargebacks/?id=" + id,
                            "chargeback",
                            rs.getTimestamp("opened_at").toInstant(),
                            due == null ? null : due.toInstant(),
                            false,
                            null,
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getLong("amount_paise"),
                            false);
                })
                .list();
    }
}
