package com.idleo.controlplane.sessions.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Session leases (ADR 0016, technical design "Design principles").
 *
 * @param ttl how long each lease lasts, counted by the agent from receipt on its sleep-counting clock: 2
 *     minutes for batch
 */
@ConfigurationProperties("sessions.leases")
@Validated
record LeaseProperties(@NotNull Duration ttl) {}
