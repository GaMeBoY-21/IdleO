/**
 * The sessions module. Owns: the session lifecycle, leases, attempts, checkpoints, per-session credentials,
 * and the template catalog. Its tables live in the {@code sessions} database schema, and no other module
 * reads them (ADR 0015).
 *
 * <p>Every session follows one state machine (technical design lines 197-223): every transition is checked
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
 * <p>Endpoints, owner-only; every error is an RFC 9457 problem detail.
 *
 * <ul>
 *   <li>{@code GET /v1/templates}: what renters can run (templates/catalog.yaml).
 *   <li>{@code POST /v1/sessions}, body {@code {"templateId", "maxPricePaisePerMin", "maxSpendPaise",
 *       "vramNeededMb"?, "maxRuntimeSeconds"?}}, with an {@code Idempotency-Key}: books a batch session (201).
 *       402 if the available credit can't cover the hold (30 minutes at the maximum price, up to the maximum
 *       spend), before anything is created. The same key and body again returns the session (200); a
 *       different body is 422.
 *   <li>{@code GET /v1/sessions}, {@code GET /v1/sessions/{id}}: state, host once matched, price, the credit
 *       held, and every state change with its reason and time.
 *   <li>{@code POST /v1/sessions/{id}/cancel}: in REQUESTED, MATCHED or PROVISIONING; releases the hold,
 *       withdraws any offer, tells a started agent to stop, bills nothing; repeating it changes nothing. 409 in
 *       any other state.
 *   <li>{@code GET /v1/sessions/{id}/outputs}: a COMPLETED session's output files, each with a pre-signed
 *       download link valid for 15 minutes (ADR 0016). 409 before COMPLETED.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.sessions;

import org.springframework.modulith.ApplicationModule;
