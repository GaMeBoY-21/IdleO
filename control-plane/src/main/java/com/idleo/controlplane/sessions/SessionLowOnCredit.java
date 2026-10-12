package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * A running session's hold plus its renter's available balance now covers under 10 minutes (ADR 0026). Sent each
 * time it falls under, through {@link SessionMilestones}; whoever listens decides how often to say so.
 */
public record SessionLowOnCredit(UUID sessionId) {}
