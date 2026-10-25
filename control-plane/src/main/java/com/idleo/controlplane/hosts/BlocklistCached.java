package com.idleo.controlplane.hosts;

/**
 * A newer abuse blocklist was verified and cached by this instance; the agent gateway sends it to the agents whose streams
 * this instance holds. Published locally, never stored: each instance reads the feed itself.
 */
public record BlocklistCached(String version) {}
