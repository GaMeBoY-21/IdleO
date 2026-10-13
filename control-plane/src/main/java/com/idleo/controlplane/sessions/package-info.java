/**
 * The sessions module. Owns: the session lifecycle, leases, attempts, checkpoints, per-session credentials,
 * and the template catalog. Its tables live in the {@code sessions} database schema, and no other module
 * reads them (ADR 0015).
 *
 * <p>Every session follows one state machine (technical design lines 213-239): every transition is checked
 * against one table of allowed moves, bumps the session's version and writes an append-only {@code
 * session_events} row, all in one transaction.
 *
 * <p>Public API for other modules:
 *
 * <ul>
 *   <li>{@link com.idleo.controlplane.sessions.SessionBookings}: what the scheduler reads and changes while
 *       booking a session;
 *   <li>the agent gateway's ports, implemented here (ADR 0016): credentials for each StartSession delivery,
 *       agents' session statuses, and reconciliation when an agent reconnects;
 *   <li>events: {@link com.idleo.controlplane.sessions.SessionRequested} when a renter books, or a session
 *       whose host was lost needs its next attempt matched, and {@link
 *       com.idleo.controlplane.sessions.SessionCancelled} when a session is cancelled before it runs.
 * </ul>
 *
 * <p>Also public: {@link com.idleo.controlplane.sessions.SessionRequests}, the one check of a booking against its
 * template, which the scheduler's quote uses too; {@link com.idleo.controlplane.sessions.SessionUpdates}, through
 * which metering says a settlement changed a session; and {@link com.idleo.controlplane.sessions.CreateSessionRequest}.
 *
 * <p>Endpoints, owner-only (another renter's session or upload is {@code not-found}); every error is an RFC 9457
 * problem detail with a type from the catalog (docs/api/problems.md, ADR 0019).
 *
 * <ul>
 *   <li>{@code GET /v1/templates}: what renters can run, with each template's parameters and input.
 *   <li>{@code POST /v1/uploads} and {@code /part-urls}, {@code /complete}, {@code GET}, {@code DELETE}: files sent
 *       straight to storage through pre-signed multipart links (ADR 0019).
 *   <li>{@code POST /v1/sessions}, with an {@code Idempotency-Key}: books a batch session with the template's
 *       {@code params} and an {@code input} (a ready upload or an https URL). {@code insufficient-credit} if the
 *       available credit can't cover the hold, before anything is created.
 *   <li>{@code GET /v1/sessions} (paged: cursor, limit, active), {@code GET /v1/sessions/{id}}: state, price, cost,
 *       {@code settled} and {@code endedAt}, params and input, the GPU, checkpoints, and every state change.
 *   <li>{@code GET /v1/sessions/{id}/events}: the same view live, as Server-Sent Events, with {@code logs} events,
 *       closing once settled.
 *   <li>{@code GET /v1/sessions/{id}/logs}: the job's output in chunks, by attempt and seq.
 *   <li>{@code POST /v1/sessions/{id}/cancel}, {@code POST /v1/sessions/{id}/stop}: as before (ADRs 0015, 0017).
 *   <li>{@code GET /v1/sessions/{id}/outputs}: a COMPLETED session's output files, each with a pre-signed
 *       download link valid for 15 minutes (ADR 0016).
 * </ul>
 *
 * <p>The admin console (ADR 0027): {@code GET /admin/v1/sessions} (by id, renter, host, state and when booked) and
 * {@code GET /admin/v1/sessions/{id}} (its timeline, attempts and charge, never its parameters or inputs), and
 * {@code POST /admin/v1/sessions/{id}/stop} with a reason: a running session is stopped at once, without a checkpoint,
 * for {@code operator_stopped}, through the same stop as a renter's, and settles like any stopped session. Audited.
 */
@ApplicationModule
package com.idleo.controlplane.sessions;

import org.springframework.modulith.ApplicationModule;
