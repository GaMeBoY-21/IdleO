package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ControlRoom;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Identity's step of the renters' funnel (ADR 0056): everyone who ever signed up and wasn't erased. */
@Component
class IdentityControlRoom implements ControlRoom.FunnelSource {

    private final JdbcClient jdbc;

    IdentityControlRoom(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ControlRoom.Step> steps() {
        long signedUp = jdbc.sql("select count(*) from identity.users where status <> 'erased'")
                .query(Long.class)
                .single();
        return List.of(new ControlRoom.Step("renters", 1, "signed up", signedUp));
    }

    @Override
    public List<ControlRoom.Reached> reached(java.time.Instant since) {
        return jdbc.sql("select id, created_at from identity.users where status <> 'erased' and created_at >= :from")
                .param("from", java.sql.Timestamp.from(since))
                .query((rs, n) -> new ControlRoom.Reached(
                        "renters",
                        1,
                        "signed up",
                        rs.getObject("id", java.util.UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
