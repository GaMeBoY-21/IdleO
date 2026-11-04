package com.idleo.controlplane.identity.internal;

import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Which approval the current work runs under, for the audit log's rows (ADR 0051): this thread's, for as long as it runs. */
final class ApprovalContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private ApprovalContext() {}

    static <T> T running(UUID approval, Supplier<T> work) {
        var outer = CURRENT.get();
        CURRENT.set(approval);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(outer);
            }
        }
    }

    static @Nullable UUID current() {
        return CURRENT.get();
    }
}
