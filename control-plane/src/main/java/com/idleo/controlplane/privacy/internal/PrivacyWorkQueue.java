package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletions and exports (ADR 0053), from when they're asked for until they're done. Each runs by itself; it's in the
 * inbox because the law's clock runs on it, and an admin needs to see one that's stuck.
 */
@Component
class PrivacyWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    PrivacyWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "data-request";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.PRIVACY_READ;
    }

    @Override
    public List<Item> open() {
        var items = new ArrayList<Item>();
        items.addAll(jdbc.sql("select * from privacy.deletion_requests where state in ('scheduled', 'erasing')")
                .query((rs, n) -> {
                    var user = rs.getObject("user_id", UUID.class);
                    return item(
                            "deletion:" + rs.getObject("id", UUID.class),
                            user,
                            "A deletion, " + rs.getString("state"),
                            rs.getTimestamp("requested_at").toInstant());
                })
                .list());
        items.addAll(jdbc.sql("select * from privacy.exports where state in ('requested', 'failed')")
                .query((rs, n) -> {
                    var user = rs.getObject("user_id", UUID.class);
                    return item(
                            "export:" + rs.getObject("id", UUID.class),
                            user,
                            "A copy of their data, " + rs.getString("state"),
                            rs.getTimestamp("requested_at").toInstant());
                })
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
                "/admin/users/?id=" + user,
                "data-request",
                at,
                null,
                false,
                null,
                null,
                0,
                false);
    }
}
