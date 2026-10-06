package com.idleo.controlplane.sessions.internal;

import java.util.List;

/** Mints storage credentials limited to {@code prefixes}, read-write or read-only: MinIO or R2 (ADR 0016). */
interface GrantIssuer {

    StorageGrant issue(List<String> prefixes, boolean readWrite);
}
