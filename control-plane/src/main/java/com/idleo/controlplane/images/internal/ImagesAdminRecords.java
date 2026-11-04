package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in images (ADR 0051). */
@Component
class ImagesAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    ImagesAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of("image", AdminRecords.byId(jdbc, "id", "select version from images.images where id = :id"));
    }
}
