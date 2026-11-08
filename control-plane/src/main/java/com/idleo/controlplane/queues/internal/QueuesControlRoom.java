package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.ControlRoom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The inbox, for the live dashboard's health (ADR 0056): items past their target, whoever's they are. */
@Component
class QueuesControlRoom implements ControlRoom.HealthSource {

    private final JdbcClient jdbc;

    QueuesControlRoom(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        long breaching = jdbc.sql("select count(*) from queues.items where state <> 'resolved' and due_at <= :now")
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        return List.of(new ControlRoom.Check(
                "inbox targets",
                breaching == 0,
                breaching == 0
                        ? "nothing past its target"
                        : breaching + " item" + (breaching == 1 ? "" : "s") + " past its target"));
    }
}
