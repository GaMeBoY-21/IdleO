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
}
