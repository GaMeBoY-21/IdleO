package com.idleo.controlplane.trust;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An admin resolved a renter's report on a session (ADR 0028, ADR 0039): the renter is told how, with the admin's
 * reason, and the refund given back as credit, if any. Published in the resolution's own transaction; durable.
 *
 * @param refundPaise what was given back, or null when the report was resolved without a refund
 * @param reason the admin's words, which the renter reads
 */
public record ReportResolved(
        UUID disputeId,
        UUID sessionId,
        UUID renterId,
        @Nullable Long refundPaise,
        String reason) {}
