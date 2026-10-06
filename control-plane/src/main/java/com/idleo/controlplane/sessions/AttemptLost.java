package com.idleo.controlplane.sessions;

import java.util.UUID;

/** An attempt lost its host (ADR 0016); its minutes after its last complete checkpoint are given back (ADR 0017). */
public record AttemptLost(UUID sessionId, int attempt) {}
