/**
 * The support module (ADR 0037). Owns: messages to us and our answers, as tickets.
 *
 * <p>Its tables live in the {@code support} database schema, and no other module reads them.
 *
 * <ul>
 *   <li>{@code POST /v1/support/tickets}: from the Contact page, signed in or not, or from Account, Help: a topic, a
 *       message of at most 5,000 characters and the address to answer; signed in, a session or machine of the
 *       person's own may be named. Limited per person or address ({@code limits.support-messages-per-hour}), and
 *       refused when it's plainly a machine (a hidden field filled, or sent within seconds of the page opening). Every
 *       ticket is acknowledged at once by email, with its number. A privacy request or a grievance has a due date,
 *       {@code legal.support.response-due} after it arrived.
 *   <li>{@code GET /v1/support/tickets}, {@code GET /v1/support/tickets/{id}} and {@code POST
 *       /v1/support/tickets/{id}/messages}: the person's own tickets, read and answered in the app; another person's
 *       is {@code not-found}.
 *   <li>{@code GET /v1/public/contact}: the grievance officer the Contact and Privacy pages name ({@code
 *       legal.grievance.*}), and the topics.
 *   <li>{@code /admin/v1/tickets}: the list by due date, overdue first, filtered by topic, status and age; a ticket
 *       with its messages; an answer, emailed with the ticket's number in the subject; a change of status. Each audited.
 *   <li>{@code POST /admin/v1/notices}: a written notice emailed to chosen people, as a breach requires
 *       (docs/runbooks/breach.md), audited.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.support;

import org.springframework.modulith.ApplicationModule;
