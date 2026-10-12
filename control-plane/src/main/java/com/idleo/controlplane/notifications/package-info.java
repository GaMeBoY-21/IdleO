/**
 * The notifications module. Owns: what each person is told and what they chose to be told about, in the app and
 * by email (ADR 0024, ADR 0026).
 *
 * <p>Its tables live in the {@code notifications} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 *
 * <p>Other modules announce; this one listens. Each listener is durable and retried (Spring Modulith's event
 * registry), and each message is made once for the event that caused it:
 *
 * <ul>
 *   <li>{@code SessionSettled}: a session that ran has ended and its charge is final: "finished", "failed", or
 *       "stopped" for credit or the spending cap; one the renter stopped themselves says nothing.
 *   <li>{@code SessionNeverRan}: no GPU was free, or it failed before it started.
 *   <li>{@code SessionLowOnCredit}: under 10 minutes of credit, once for each session.
 *   <li>{@code AttemptLost}: the renter's session is moving to another GPU; the machine's owner is told it went
 *       offline, and never who rented it.
 * </ul>
 *
 * <p>A person's choices (ADR 0024) decide each message: in the app, by email, both or neither, except the kinds
 * about money and safety, which are always both. Email goes through one {@code EmailSender}, SMTP today: Mailpit
 * locally, a provider later. It is handed over at most once for each message, and a refused one is retried.
 *
 * <ul>
 *   <li>{@code GET /v1/notifications}: the person's messages, newest first, with a cursor and the unread count.
 *   <li>{@code POST /v1/notifications/read}: marks every message up to an id read.
 *   <li>{@code GET /v1/me} carries {@code unreadNotifications}, through identity's {@code UnreadNotifications}
 *       port, which this module implements.
 *   <li>{@code GET/PUT /v1/me/notifications}: the choices, every kind with its title, its two switches, and
 *       whether it is locked on and why. 422 {@code notification-locked} when a locked kind is switched off; 400
 *       {@code validation-failed} for an unknown or repeated kind.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.notifications;

import org.springframework.modulith.ApplicationModule;
