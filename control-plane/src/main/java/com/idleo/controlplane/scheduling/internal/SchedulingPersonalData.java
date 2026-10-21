package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.hosts.HostOwners;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An offer names the GPU it was for by the GPU's own uuid, a hardware id of the owner's machine (ADR 0037): erasing
 * the owner's account empties it. The offers stay, as the record of how sessions were matched.
 */
@Component
class SchedulingPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final HostOwners owners;

    SchedulingPersonalData(JdbcClient jdbc, HostOwners owners) {
        this.jdbc = jdbc;
        this.owners = owners;
    }

    @Override
    public String module() {
        return "scheduling";
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
        var hosts =
                owners.ownedBy(userId).stream().map(HostOwners.OwnedHost::id).toList();
        if (step == ErasureStep.DE_IDENTIFY && !hosts.isEmpty()) {
            jdbc.sql("update scheduling.offers set gpu_uuid = '' where host_id in (:hosts) and gpu_uuid <> ''")
                    .param("hosts", hosts)
                    .update();
        }
    }
}
