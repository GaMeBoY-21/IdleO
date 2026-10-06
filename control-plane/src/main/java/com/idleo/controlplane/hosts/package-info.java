/**
 * The hosts module. Owns: hosts, their enrolment codes and the client certificates issued to their
 * agents, and later GPUs, inventory, availability, price listings and the host score. Its tables
 * live in the {@code hosts} database schema, and no other module reads them. It is the only module
 * that issues or reads host certificates (ADR 0010).
 *
 * <p>Public API for other modules:
 *
 * <ul>
 *   <li>{@link com.idleo.controlplane.hosts.HostEnrolment} trades a one-time enrolment code and a
 *       certificate signing request for a host certificate;
 *   <li>{@link com.idleo.controlplane.hosts.HostCertificates} authenticates an agent by its
 *       certificate and renews certificates in their last 10 days;
 *   <li>{@link com.idleo.controlplane.hosts.HostAgentReports} records the OS, agent version and GPUs
 *       an agent reports, and when it was last seen.
 * </ul>
 *
 * <p>{@link com.idleo.controlplane.hosts.HostListings} gives a host's listing. Hosts are activated and
 * suspended by operator commands, audited in {@code hosts.status_changes} (docs/runbooks/hosts.md).
 *
 * <p>A port other modules implement: {@link com.idleo.controlplane.hosts.HostPresence}, whether a host
 * is online, answered by the agent gateway (ADR 0013). {@link com.idleo.controlplane.hosts.HostOwners} says who
 * owns a host, for modules that show a host's data to its owner only.
 *
 * <p>Endpoints, every one owner-only; another user's host is a 404, so ids can't be probed. Every
 * error is an RFC 9457 problem detail.
 *
 * <ul>
 *   <li>{@code POST /v1/hosts}, body {@code {"name": …}}: creates a pending host. It needs an {@code
 *       Idempotency-Key} header: 400 without it, the stored response for a repeat, 422 when the key
 *       is reused with a different body, and 409 while the first request is still running.
 *   <li>{@code POST /v1/hosts/{id}/enrolment-code}: issues a one-time code for the host's agent and
 *       replaces any earlier one. The code is shown once, works once, and expires after an hour.
 *   <li>{@code PUT /v1/hosts/{id}/listing}, body {@code {"batchPricePaisePerMin": …, "gpuTempCapC": 60–95,
 *       "maxConcurrentSessions": 1}}: the host's batch price and limits (ADR 0015). The temperature cap goes
 *       to the agent in Welcome.
 *   <li>{@code GET /v1/hosts}: my hosts. {@code GET /v1/hosts/{id}}: one of my hosts. Each says whether
 *       it is online now, when its agent was last seen (to the minute), and the GPUs it reported.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.hosts;

import org.springframework.modulith.ApplicationModule;
