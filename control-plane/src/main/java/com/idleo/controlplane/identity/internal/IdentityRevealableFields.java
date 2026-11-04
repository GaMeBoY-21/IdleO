package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.RevealableFields;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What identity keeps that can be revealed: an account's email, and the addresses in the two logs. */
@Component
class IdentityRevealableFields implements RevealableFields {

    private final JdbcClient jdbc;

    IdentityRevealableFields(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<String, Optional<Revealed>>> fields() {
        return Map.of(
                "email",
                id -> read("select id as subject, email as value from identity.users where id = :id", id),
                "security_event_ip",
                id -> read(
                        "select user_id as subject, host(address) as value from identity.security_events where id = :id",
                        id),
                "audit_ip",
                id -> read(
                        "select actor_user_id as subject, host(ip) as value from identity.audit_log where id = :id",
                        id));
    }

    private Optional<Revealed> read(String sql, String id) {
        UUID record;
        try {
            record = UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
        return jdbc
                .sql(sql)
                .param("id", record)
                .query((rs, n) -> {
                    var value = rs.getString("value");
                    return value == null ? null : new Revealed(rs.getObject("subject", UUID.class), value);
                })
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
