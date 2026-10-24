/**
 * The trust module. Owns: ratings and disputes (renters' reports of a problem), and later canary jobs, abuse flags
 * and bans.
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
 *
 * <p>Reports (ADR 0028):
 *
 * <ul>
 *   <li>{@code POST /v1/disputes {sessionId, reason, details?}}, with an {@code Idempotency-Key}: the renter's own
 *       ended session, within 7 days of it ending ({@code report-window-closed} after), one open report a session
 *       ({@code report-already-open}); the reason is {@code didnt_start}, {@code failed}, {@code slow}, {@code
 *       host_stopped}, {@code wrong_output}, {@code charged_wrong} or {@code other}, the details up to 2,000 characters. While it's open, the
 *       session's host earnings are held from payout (billing's {@link com.idleo.controlplane.billing.PayoutHolds}).
 *   <li>{@code GET /v1/disputes?session=&cursor=&limit=} and {@code GET /v1/disputes/{id}}: the renter's reports,
 *       newest first, {@code open}, {@code refunded} or {@code not_refunded}, with our answer once resolved.
 *   <li>The admin console: {@code GET /admin/v1/disputes?state=} (open first, oldest first) and {@code
 *       /admin/v1/disputes/{id}} (the report, the session's end, what it cost and who kept what, from the ledger, and
 *       its rating); {@code POST /admin/v1/disputes/{id}/refund {amountPaise, reason}} and {@code /no-refund
 *       {reason}}, once each report, with a reason the renter reads, and optionally {@code flagHost}: a mark on the
 *       session's host's record that admins see ({@code GET /admin/v1/disputes/host-flags?host=}), for the host score to use.
 *       Audited in the same transaction, which also publishes {@link ReportResolved} for the renter's email. The
 *       session's log is read from the sessions module's {@code GET /admin/v1/sessions/{id}/log}, with a reason,
 *       audited (ADR 0039).
 *   <li>A refund goes through billing's {@link com.idleo.controlplane.billing.SessionRefunds}: up to what the renter
 *       paid for the session net, from the platform's commission first and then the hosts' shares, in one balanced
 *       ledger transaction whose {@code txn_id} is a name-based UUID of the report.
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.trust;

import org.springframework.modulith.ApplicationModule;
