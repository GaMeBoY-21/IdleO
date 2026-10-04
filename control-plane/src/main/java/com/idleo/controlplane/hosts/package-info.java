/**
 * The hosts module. Owns: hosts, their enrolment codes and the client certificates issued to their
 * agents, and later GPUs, inventory, availability, price listings and the host score. Its tables
 * live in the {@code hosts} database schema, and no other module reads them.
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
 *   <li>{@code GET /v1/hosts}: my hosts. {@code GET /v1/hosts/{id}}: one of my hosts.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.hosts;

import org.springframework.modulith.ApplicationModule;
