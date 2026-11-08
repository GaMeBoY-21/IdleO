package com.idleo.controlplane.hosts.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When a staged rollout pauses by itself (ADR 0056): its hosts against the rest, over {@code window}.
 *
 * @param checkEvery how often it's checked
 * @param window how far back sessions and connections are counted
 * @param minSessions the fewest of its hosts' sessions that say anything about their failure rate
 * @param failureMargin how many more of its hosts' sessions may be lost than the rest's, as a share (0.10: ten points)
 * @param minReconnects the fewest reconnects a host a day of its hosts that can pause it
 * @param reconnectFactor how many times the rest's reconnects a host a day its hosts may reach
 */
@ConfigurationProperties("hosts.rollouts")
record RolloutProperties(
        @DefaultValue("5m") Duration checkEvery,
        @DefaultValue("24h") Duration window,
        @DefaultValue("5") int minSessions,
        @DefaultValue("0.10") double failureMargin,
        @DefaultValue("3") double minReconnects,
        @DefaultValue("2") double reconnectFactor) {}
