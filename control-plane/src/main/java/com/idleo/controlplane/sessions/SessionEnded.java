package com.idleo.controlplane.sessions;

import java.util.UUID;

/** A session that started reached a final state (ADR 0017): its usage can be closed. */
public record SessionEnded(UUID sessionId) {}
