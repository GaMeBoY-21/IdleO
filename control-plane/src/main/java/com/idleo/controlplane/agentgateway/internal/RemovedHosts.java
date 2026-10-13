package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostCertificatesRevoked;
import com.idleo.controlplane.hosts.HostRemoved;
import io.grpc.Status;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the stream of a host its owner removed (ADR 0023), or whose certificates an admin revoked (ADR 0027), at once if this instance holds it. Another instance's
 * stream closes at its next heartbeat that records the agent as seen, within a minute, and its certificates are
 * revoked either way, so the agent can't reconnect.
 */
@Component
class RemovedHosts {

    private final ConnectedAgents connected;

    RemovedHosts(ConnectedAgents connected) {
        this.connected = connected;
    }

    @TransactionalEventListener
    void on(HostRemoved event) {
        var stream = connected.get(event.hostId());
        if (stream != null) {
            stream.close(Status.PERMISSION_DENIED.withDescription("The host was removed."), "host_removed");
        }
    }

    /** An admin revoked the host's certificates (ADR 0027): the same, so it can't carry on with them. */
    @TransactionalEventListener
    void on(HostCertificatesRevoked event) {
        var stream = connected.get(event.hostId());
        if (stream != null) {
            stream.close(
                    Status.PERMISSION_DENIED.withDescription("The host's certificates were revoked."),
                    "certificates_revoked");
        }
    }
}
