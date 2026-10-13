/**
 * The trust module. Owns: ratings, and later disputes, canary jobs, abuse flags and bans.
 *
 * <p>Its tables live in the {@code trust} database schema, and no other module reads them. See
 * docs/plan/technical-design.md, "Control plane modules and API".
 *
 * <p>It keeps its own record of every session that ended, from the sessions module's durable {@code SessionEnded}
 * and {@code SessionNeverRan} events, so it never reads that module's tables (ADR 0028).
 *
 * <p>Ratings (ADR 0028):
 *
 * <ul>
 *   <li>{@code GET /v1/sessions/{id}/rating}: whether the session can be rated now, until when, and its rating;
 *       {@code not-found} for another person's session or one that hasn't ended.
 *   <li>{@code POST /v1/sessions/{id}/rating {stars, comment?}}: 1 to 5 stars and up to 500 characters, from the
 *       renter, for a session that ran at least a minute; given within 7 days of it ending, and changed in place
 *       within 7 days of being given. One rating a session, for the host that ran its last attempt.
 *   <li>The hosts module's {@link com.idleo.controlplane.hosts.HostRatings} port: a host's average over its sessions
 *       that ended in the last 90 days, shown on the owner's host from 5 ratings. A host never learns who rated it,
 *       or reads a comment.
 *   <li>{@code GET /admin/v1/ratings?host=&session=}: the ratings with their comments, for admins only.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.trust;

import org.springframework.modulith.ApplicationModule;
