package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.Session.Credentials;
import gpu.agent.v1.Session.RefreshCredentials;
import gpu.agent.v1.Session.StartSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0016). A StartSession or a
 * RefreshCredentials is queued without its credentials, so no secret is ever stored; they're minted fresh at each delivery, and
 * the sessions module, which already depends on the agent gateway, keeps the dependency pointing one way.
 */
public interface SessionCredentials {

    /**
     * Credentials for {@code start}, minted now. Empty if its attempt is no longer the session's current,
     * live one; the command is then withdrawn instead of sent. Throws if storage can't mint them; the
     * command stays queued and is tried again.
     */
    Optional<Credentials> mint(UUID hostId, StartSession start);

    /** {@code start} went down the host's stream at {@code sentAt}: from then on, the agent may hold a lease. */
    void delivered(UUID hostId, StartSession start, Instant sentAt);

    /**
     * Fresh credentials for a running attempt (ADR 0017), minted now, like {@link #mint}: empty if the attempt is
     * no longer current, and the command is withdrawn.
     */
    Optional<Credentials> mintRefresh(UUID hostId, RefreshCredentials refresh);

    /** {@code refresh} went down the host's stream at {@code sentAt}. */
    void refreshed(UUID hostId, RefreshCredentials refresh, Instant sentAt);
}
