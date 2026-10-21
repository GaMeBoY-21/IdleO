package com.idleo.controlplane.sessions;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The list of erased accounts, kept outside the database too (ADR 0037): one tiny object a person at {@code
 * erasures/{id}}, holding only when. A restore of the database to an earlier point can't lose an erasure that happened
 * after it: {@code reapply-erasures} reads this list as well as {@code privacy.erased_users}.
 */
public interface ErasureMarks {

    void mark(UUID userId, Instant erasedAt);

    /** Every account marked, and when. */
    Map<UUID, Instant> marked();
}
