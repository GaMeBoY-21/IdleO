package com.idleo.controlplane.sessions.internal;

/** The session states (technical design lines 197-221): six working, five final. */
enum SessionState {
    REQUESTED,
    MATCHED,
    PROVISIONING,
    RUNNING,
    HOST_LOST,
    FINISHING,
    CANCELLED(true),
    NO_HOST_FREE(true),
    FAILED(true),
    COMPLETED(true),
    ENDED_REFUNDED(true);

    final boolean isFinal;

    SessionState() {
        this(false);
    }

    SessionState(boolean isFinal) {
        this.isFinal = isFinal;
    }
}
