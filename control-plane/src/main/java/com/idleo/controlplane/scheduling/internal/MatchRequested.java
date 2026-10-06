package com.idleo.controlplane.scheduling.internal;

import java.util.UUID;

/**
 * A session went back to REQUESTED after a host declined or let its offer expire, and needs the next
 * candidate. Published in the same transaction as the decline, so the registry delivers it even after a
 * crash (ADR 0015).
 */
public record MatchRequested(UUID sessionId) {}
