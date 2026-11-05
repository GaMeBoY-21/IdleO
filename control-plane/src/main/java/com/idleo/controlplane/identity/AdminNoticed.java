package com.idleo.controlplane.identity;

import java.util.UUID;

/**
 * Something a person must be told by an admin's doing (ADR 0051, ADR 0053), or an admin about their access or an approval: a step on the ladder, an appeal's outcome, a sign-in from somewhere new, or a
 * request waiting out its cooling-off delay. Published in the transaction that caused it; notifications delivers it
 * once for {@code dedupeKey}, in the app and by email.
 *
 * @param link a path in the web app, such as {@code /admin/approvals/}
 */
public record AdminNoticed(UUID userId, String dedupeKey, String title, String body, String link) {}
