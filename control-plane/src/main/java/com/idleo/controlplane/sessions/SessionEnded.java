package com.idleo.controlplane.sessions;

import java.time.Instant;
import java.util.UUID;

/**
 * A session that started reached a final state (ADR 0017): its usage can be closed. It also says whose it was, the
 * host that ran its last attempt, and when it ran and ended, so the trust module keeps its own record of it for
 * ratings and reports (ADR 0028) without reading this module's tables. Durable.
 *
 * @param state its final state: COMPLETED, FAILED, ENDED_REFUNDED or CANCELLED
 * @param startedAt its minute 0, the first RUNNING
 */
public record SessionEnded(
        UUID sessionId, UUID renterId, UUID hostId, String state, Instant startedAt, Instant endedAt) {}
