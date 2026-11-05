package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Minutes only one witness saw (ADR 0017), waiting for a decision (ADR 0053). */
@Component
class MeteringWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    MeteringWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "review-minute";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.REVIEW_READ;
    }

    @Override
    public List<Item> open() {
        return jdbc.sql("""
                        select r.session_id, r.minute_index, r.reason, r.created_at, m.renter_id
                        from metering.review_minutes r
                        join metering.metered_sessions m on m.session_id = r.session_id
                        where r.resolution is null
                        """)
                .query((rs, n) -> {
                    var session = rs.getObject("session_id", UUID.class);
                    int minute = rs.getInt("minute_index");
                    return new Item(
                            session + ":" + minute,
                            "session",
                            session,
                            rs.getObject("renter_id", UUID.class),
                            "Minute " + minute + ": " + rs.getString("reason").replace('_', ' '),
                            "/admin/review/",
                            "review-minute",
                            rs.getTimestamp("created_at").toInstant(),
                            null,
                            false,
                            null,
                            null,
                            0,
                            false);
                })
                .list();
    }
}
