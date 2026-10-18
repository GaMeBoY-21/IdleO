package com.idleo.controlplane.hosts;

/**
 * A newer release manifest was verified and cached by this instance; the agent gateway offers it to the agents whose
 * streams this instance holds. Published locally, never stored: each instance reads the feed itself.
 */
public record AgentReleaseCached(String version) {}
