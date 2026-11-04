package com.idleo.controlplane.identity;

import java.util.UUID;

/**
 * Something an admin must be told about their access or an approval (ADR 0051): a sign-in from somewhere new, or a
 * request waiting out its cooling-off delay. Published in the transaction that caused it; notifications delivers it
 * once for {@code dedupeKey}, in the app and by email.
 *
 * @param link a path in the web app, such as {@code /admin/approvals/}
 */
public record AdminNoticed(UUID userId, String dedupeKey, String title, String body, String link) {}
