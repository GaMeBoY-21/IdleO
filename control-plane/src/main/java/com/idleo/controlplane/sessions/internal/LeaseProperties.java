package com.idleo.controlplane.sessions.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Session leases (ADR 0016).
 *
 * @param ttl how long each lease lasts, counted by the agent from receipt on its sleep-counting clock: 2
 *     minutes for batch
 * @param renewEvery how often a live attempt's lease is renewed: every 30 seconds, so it survives three
 *     missed renewals
 * @param transitAllowance how long after {@code lease_expires_at} the host counts as lost. The agent counts
 *     its lease from receipt, which is after the send the control plane records, by the message's time in
 *     transit. The agent drops a connection whose keepalive pings go unanswered (30 s interval plus 20 s
 *     timeout), discarding anything still in flight on it, so a message reaches it at most 50 seconds after
 *     it was sent. Waiting that long more means the control plane never re-matches before the agent has
 *     stopped (ADR 0016).
 * @param lapseSweepEvery how often lapsed leases are looked for
 */
@ConfigurationProperties("sessions.leases")
@Validated
record LeaseProperties(
        @NotNull Duration ttl,
        @NotNull Duration renewEvery,
        @NotNull Duration transitAllowance,
        @NotNull Duration lapseSweepEvery) {}
