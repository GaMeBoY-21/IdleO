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
 *
 * <p>The review queue (ADR 0027): minutes only one witness saw, or that the cap or the credit couldn't pay for, wait
 * in {@code metering.review_minutes} for an admin: {@code GET /admin/v1/review-minutes}, oldest first, and {@code POST
 * /admin/v1/review-minutes/{session}/{minute}/charge} or {@code /waive}, each with a reason, decided once and
 * audited. A charged minute is a settlement of its own through billing's {@code SessionCharges}, at its attempt's
 * price, never past the cap or the renter's credit; a waived one moves no money. It implements identity's {@code
 * DashboardFigures} for the settlement backlog and the review queue's size.
 */
@ApplicationModule
package com.idleo.controlplane.metering;

import org.springframework.modulith.ApplicationModule;
