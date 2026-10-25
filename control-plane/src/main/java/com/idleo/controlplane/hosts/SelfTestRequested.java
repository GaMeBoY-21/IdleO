package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * A host's owner or an admin asked for its full self-test to run now: the agent gateway sends the host's agent a
 * {@code RunSelfTest} for this run. Published in the request's own transaction; durable.
 */
public record SelfTestRequested(UUID hostId, UUID runId) {}
