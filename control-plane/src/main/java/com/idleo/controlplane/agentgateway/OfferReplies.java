package com.idleo.controlplane.agentgateway;

import java.util.UUID;

/**
 * A port the agent gateway declares and the scheduler implements (ADR 0015): an agent's answer to an
 * offer. The scheduler already depends on the agent gateway, so this keeps every dependency pointing one
 * way. The gateway acks the reply only after this returns.
 */
public interface OfferReplies {

    /**
     * @param declineReason the reason, lowercase without its prefix (for example {@code owner_active}), or
     *     empty when accepted
     */
    void onReply(UUID hostId, UUID offerId, boolean accepted, String declineReason);
}
