package com.idleo.controlplane;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable events between modules (Spring Modulith's {@code modulith.event_publication}) carry ids, and a
 * completed one is only a record of delivery (ADR 0037): erasure deletes the completed ones that name the person.
 * Ones not yet delivered stay, so no module misses its event.
 */
@Component
class EventPublicationsPersonalData implements PersonalData {

    private final JdbcClient jdbc;

    EventPublicationsPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "events";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of();
    }

    @Override
    public List<Part> export(UUID userId) {
        return List.of();
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        if (step == ErasureStep.DELETE_RECORDS) {
            jdbc.sql("""
                            delete from modulith.event_publication
                            where completion_date is not null and strpos(serialized_event, :user) > 0
                            """).param("user", userId.toString()).update();
        }
    }
}
