package com.idleo.controlplane.sessions.internal;

import java.time.Duration;
import java.util.List;

/**
 * Short-lived storage credentials scoped to a few prefixes of one bucket, enforced by storage itself (ADR
 * 0016). Its secrets never reach a table or a log: {@link #toString} leaves them out.
 */
record StorageGrant(
        String endpoint,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        List<String> prefixes,
        boolean readWrite,
        Duration ttl) {

    StorageGrant {
        prefixes = List.copyOf(prefixes);
    }

    @Override
    public String toString() {
        return "StorageGrant[bucket=" + bucket + ", prefixes=" + prefixes + ", readWrite=" + readWrite + ", ttl=" + ttl
                + "]";
    }
}
