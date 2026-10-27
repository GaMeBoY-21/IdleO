package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * This control-plane process's id, fixed for its lifetime. Presence names the instance that holds
 * each host's stream, so commands can be routed to it later.
 */
@Component
class InstanceId {

    private static final Logger log = LoggerFactory.getLogger(InstanceId.class);

    private final UUID value;

    InstanceId(IdGenerator ids) {
        this.value = ids.newId();
        log.info("Control-plane instance id {}", value);
    }

    UUID value() {
        return value;
    }
}
