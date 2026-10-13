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
 * owns a host, for modules that show a host's data to its owner only. {@link com.idleo.controlplane.hosts.HostGpus}
 * names the GPU that ran a session, never the host's identity.
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
 *   <li>{@code GET /v1/hosts}: my hosts, not the ones I removed. {@code GET /v1/hosts/{id}}: one of my hosts.
 *       Each says whether it is online now and since when, when its agent was last seen (to the minute), the
 *       agent's version and whether it must update, the GPUs it reported, whether it's paused (and until when),
 *       and the session it's running now with what it has earned from it so far, never for whom (ADR 0023).
 *   <li>{@code POST /v1/hosts/{id}/pause}, body {@code {"until": …}} optional, within 7 days
 *       ({@code pause-too-long} otherwise), and {@code POST /v1/hosts/{id}/resume}: a paused host gets no new
 *       offers and a running session finishes; a pause with an end ends by itself.
 *   <li>{@code PATCH /v1/hosts/{id}}, body {@code {"name": …}}: renames it. {@code DELETE /v1/hosts/{id}}: removes it
 *       for good (409 {@code host-busy} while it runs a session): its certificates are revoked, its agent stream
 *       closes and it leaves the lists; the row stays, because the ledger points at it, and it answers 410
 *       {@code host-removed} from then on.
 *   <li>{@code GET /v1/hosts/{id}/telemetry}: the latest GPU readings and the last hour, a point a minute, kept in
 *       Valkey. For the owner's screen only; billing never reads it.
 * </ul>
 *
 * <p>The listing's {@code keepFreeGb} (10 to 500, default 20) is the disk the owner keeps for themselves: a session
 * is offered only when the host's free disk less this still covers its files. Ports other modules implement:
 * {@link com.idleo.controlplane.hosts.HostWork} (sessions: is the host busy, and what it's running),
 * {@link com.idleo.controlplane.hosts.HostTelemetry} (agent gateway), {@link
 * com.idleo.controlplane.hosts.HostRatings} (trust: the host's average rating, shown from 5 ratings, ADR 0028). {@link com.idleo.controlplane.hosts.HostRemoved}
 * is published when an owner removes a host.
 *
 * <p>The admin console (ADR 0027): {@code GET /admin/v1/hosts} and {@code /admin/v1/hosts/{id}} (its GPUs,
 * certificates, status history and telemetry), and {@code POST /admin/v1/hosts/{id}/activate}, {@code /suspend} and
 * {@code /revoke-certificates}, each with a reason. Activating and suspending call the same service as the {@code
 * activate-host} and {@code suspend-host} commands; revoking publishes {@link
 * com.idleo.controlplane.hosts.HostCertificatesRevoked}, which closes the agent's stream. Every activation,
 * suspension, certificate issued and certificate revoked is in the audit log.
 */
@ApplicationModule
package com.idleo.controlplane.hosts;

import org.springframework.modulith.ApplicationModule;
