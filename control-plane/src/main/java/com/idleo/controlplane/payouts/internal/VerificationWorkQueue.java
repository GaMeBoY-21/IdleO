package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** PANs and UPI IDs waiting for an admin (ADR 0053). */
@Component
class VerificationWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    VerificationWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "verification";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.VERIFICATIONS_READ;
    }

    @Override
    public List<Item> open() {
        var items = new ArrayList<Item>();
        items.addAll(jdbc.sql("select user_id, submitted_at from payouts.pans where state = 'review'")
                .query((rs, n) -> {
                    var user = rs.getObject("user_id", UUID.class);
                    return item(
                            "pan:" + user,
                            user,
                            "A PAN to check",
                            rs.getTimestamp("submitted_at").toInstant());
                })
                .list());
        items.addAll(jdbc.sql("select id, user_id, checked_at from payouts.methods where state = 'review'")
                .query((rs, n) -> item(
                        "upi:" + rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        "A UPI ID to check",
                        rs.getTimestamp("checked_at").toInstant()))
                .list());
        return items;
    }

    private static Item item(String source, UUID user, String title, java.time.Instant at) {
        return new Item(
                source,
                "user",
                user,
                user,
                title,
                "/admin/verification/",
                "verification",
                at,
                null,
                false,
                null,
                null,
                0,
                false);
    }
}
