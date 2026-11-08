package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.ControlRoom;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The payouts module's step of the hosts' funnel (ADR 0056): owners RazorpayX has paid at least once. */
@Component
class PayoutsControlRoom implements ControlRoom.FunnelSource {

    private final JdbcClient jdbc;

    PayoutsControlRoom(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ControlRoom.Step> steps() {
        long paid = jdbc.sql("select count(distinct user_id) from payouts.payouts where status = 'processed'")
                .query(Long.class)
                .single();
        return List.of(new ControlRoom.Step("hosts", 4, "first payout", paid));
    }

    @Override
    public List<ControlRoom.Reached> reached(java.time.Instant since) {
        return jdbc.sql("""
                        select user_id, min(processed_at) as first from payouts.payouts
                        where status = 'processed' and processed_at >= :from group by user_id
                        """)
                .param("from", java.sql.Timestamp.from(since))
                .query((rs, n) -> new ControlRoom.Reached(
                        "hosts",
                        4,
                        "first payout",
                        rs.getObject("user_id", java.util.UUID.class),
                        rs.getTimestamp("first").toInstant()))
                .list();
    }
}
