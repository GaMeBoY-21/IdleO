package com.idleo.controlplane.sessions.internal;

import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sessions written straight to the store, without the event that would wake the scheduler. */
final class SessionTestData {

    /** UUIDv7, time-ordered, like every real id. */
    private static final com.fasterxml.uuid.impl.TimeBasedEpochGenerator IDS =
            com.fasterxml.uuid.Generators.timeBasedEpochGenerator();

    private SessionTestData() {}

    static UUID requested(
            SessionStore store, PlatformTransactionManager transactions, UUID renterId, int maxPrice, long maxSpend) {
        var id = IDS.generate();
        var now = store.now();
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> store.insert(new SessionStore.Session(
                        id,
                        renterId,
                        null,
                        "batch",
                        SessionState.REQUESTED,
                        1,
                        "cuda-smoke-test",
                        "nvidia/cuda:12.8.1-base-ubuntu24.04@sha256:" + "1".repeat(64),
                        1024,
                        maxPrice,
                        null,
                        maxSpend,
                        300,
                        "direct-" + id,
                        "hash",
                        now,
                        0,
                        now,
                        null,
                        null,
                        java.util.Map.of(),
                        null,
                        null)));
        return id;
    }
}
