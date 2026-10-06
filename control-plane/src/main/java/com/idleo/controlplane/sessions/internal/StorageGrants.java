package com.idleo.controlplane.sessions.internal;

import java.util.ArrayList;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The per-attempt storage grants (ADR 0007, proto/README.md "Grants", ADR 0016), minted fresh each time a
 * StartSession is delivered and never stored:
 *
 * <ul>
 *   <li>{@code attempt_grant}: read-write on {@code sessions/{id}/attempt-{n}/} only;
 *   <li>{@code read_grant}: read-only on the session's input and the resume checkpoint's folder; absent when
 *       there's neither.
 * </ul>
 */
@Component
class StorageGrants {

    private final GrantIssuer issuer;

    StorageGrants(GrantIssuer issuer) {
        this.issuer = issuer;
    }

    record AttemptGrants(
            StorageGrant attemptGrant, @Nullable StorageGrant readGrant) {}

    /** A checkpoint to resume from: the attempt that wrote it and its seq. */
    record ResumeFrom(int attempt, int seq) {}

    AttemptGrants mint(UUID sessionId, int attempt, @Nullable ResumeFrom resumeFrom, boolean hasInput) {
        var attemptGrant = issuer.issue(java.util.List.of(StorageLayout.attemptPrefix(sessionId, attempt)), true);
        var readPrefixes = new ArrayList<String>();
        if (hasInput) {
            readPrefixes.add(StorageLayout.inputPrefix(sessionId));
        }
        if (resumeFrom != null) {
            readPrefixes.add(StorageLayout.checkpointFolder(sessionId, resumeFrom.attempt(), resumeFrom.seq()));
        }
        var readGrant = readPrefixes.isEmpty() ? null : issuer.issue(readPrefixes, false);
        return new AttemptGrants(attemptGrant, readGrant);
    }
}
