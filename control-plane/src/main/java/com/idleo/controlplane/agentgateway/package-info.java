/**
 * The agent gateway module. Owns: the agents' gRPC transport, their streams, presence, the durable agent
 * command queue and routing to the right instance.
 *
 * <p>It serves {@code EnrollmentService.Enroll} (ADR 0010) and {@code AgentGatewayService} (ADR 0011) on the
 * control plane's one gRPC TLS port, handing enrolment and certificates to the hosts module; it never
 * touches codes, keys or certificate records itself.
 *
 * <p>Public API for other modules:
 *
 * <ul>
 *   <li>{@link com.idleo.controlplane.agentgateway.AgentCommands}: the durable queue for everything sent to an
 *       agent (ADR 0015);
 *   <li>{@link com.idleo.controlplane.agentgateway.AgentStreams}: unqueued messages, such as LeaseRenew, down
 *       a stream this instance holds (ADR 0016);
 *   <li>{@link com.idleo.controlplane.agentgateway.AgentPresence}: live agents, for the scheduler.
 * </ul>
 *
 * <p>After Welcome, and whenever the host's standing or earnings change, it queues {@code HostStatus} for the host's
 * tray, and {@code UpdateAgent} when the hosts module's release feed says the host should update (ADR 0034); both
 * replace any undelivered one of their kind ({@code AgentCommands.replace}). It hands the agent's {@code
 * SelfTestReport}, {@code SetPause}, {@code Leave} and {@code UpdateResult} to the hosts module.
 *
 * <p>Ports it declares and other modules implement, so dependencies point one way: {@link
 * com.idleo.controlplane.agentgateway.OfferReplies} (scheduling), and {@link
 * com.idleo.controlplane.agentgateway.SessionCredentials}, {@link
 * com.idleo.controlplane.agentgateway.SessionStatuses} and {@link
 * com.idleo.controlplane.agentgateway.SessionReconciliation} (sessions).
 */
@ApplicationModule
package com.idleo.controlplane.agentgateway;

import org.springframework.modulith.ApplicationModule;
