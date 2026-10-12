package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * A session that ran has ended and its last charge is posted: what it cost is final (ADR 0026). Sent when
 * metering closes its usage, through {@link SessionMilestones}. Durable, like the others.
 */
public record SessionSettled(UUID sessionId) {}
