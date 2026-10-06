package com.idleo.controlplane.sessions;

import java.util.UUID;

/** A session reached its minute 0: its first attempt reported RUNNING (ADR 0017). Durable, like the others. */
public record SessionStarted(UUID sessionId) {}
