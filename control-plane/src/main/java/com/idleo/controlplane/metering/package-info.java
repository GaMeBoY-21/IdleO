/**
 * The metering module. Owns: usage minutes and the two-witness rule (ADR 0017). Its tables live in the {@code
 * metering} database schema, and no other module reads them.
 *
 * <p>Every session minute is one row of {@code metering.usage_minutes}, partitioned by month. The agent's
 * UsageReport (through the agent gateway's {@code UsageReports} port, which this module implements) sets {@code
 * agent_reported}; the control plane's own lease record, read through the sessions module's {@code
 * MeteredSessions}, sets {@code lease_alive}. Only a minute with both is billable.
 *
 * <p>It depends on sessions and the agent gateway, and listens to {@code SessionStarted} and {@code
 * SessionEnded}. It reports a session's closed usage and a credit that ran low through the sessions module's
 * {@code SessionMilestones} port, which sessions announces as events (ADR 0026). Nothing depends on it.
 */
@ApplicationModule
package com.idleo.controlplane.metering;

import org.springframework.modulith.ApplicationModule;
