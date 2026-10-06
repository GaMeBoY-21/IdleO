package com.idleo.controlplane.sessions;

import java.util.UUID;

/** A session was cancelled before it ran; any open offer for it must be withdrawn (ADR 0015). */
public record SessionCancelled(UUID sessionId) {}
