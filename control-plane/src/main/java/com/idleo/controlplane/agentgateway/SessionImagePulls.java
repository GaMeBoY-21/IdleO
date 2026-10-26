package com.idleo.controlplane.agentgateway;

import java.util.UUID;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0042): while an attempt downloads the renter's
 * own image, the agent's heartbeats say how far it has got, for "Downloading your image · 3.1 of 8.4 GB". Heartbeats
 * aren't acked, so a failure here is logged and the next heartbeat carries it again.
 */
public interface SessionImagePulls {

    void onImagePull(UUID hostId, UUID sessionId, int attempt, long doneBytes, long totalBytes);
}
