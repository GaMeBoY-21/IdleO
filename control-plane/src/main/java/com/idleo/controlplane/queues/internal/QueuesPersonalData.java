package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.PersonalData;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the inbox holds about a person (ADR 0053, docs/reference/personal-data-map.md): that some work was theirs, and why
 * an admin moved it up. The work itself is in its own module, which answers for it.
 */
@Component
class QueuesPersonalData implements PersonalData {

    private final JdbcClient jdbc;

    QueuesPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "queues";
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
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DE_IDENTIFY) {
            return;
        }
        jdbc.sql("""
                        update queues.items
                        set person_id = null, override_reason = null,
                            subject_id = case when subject_kind = 'user' then null else subject_id end
                        where person_id = :user
                        """).param("user", userId).update();
    }
}
