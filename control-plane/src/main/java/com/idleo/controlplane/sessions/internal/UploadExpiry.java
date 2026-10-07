package com.idleo.controlplane.sessions.internal;

import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires uploads (ADR 0019): a pending one is aborted a day after it was opened, and a ready one is deleted 30 days
 * after it was completed, but never while a session that hasn't ended uses it; that one goes at a later sweep, once
 * its session has ended. One instance at a time, under an advisory lock, like the wallet check.
 */
@Component
class UploadExpiry {

    /** The advisory lock key: "uplexpir" in ASCII. */
    static final long LOCK_KEY = 0x75706c6578706972L;

    private static final Logger log = LoggerFactory.getLogger(UploadExpiry.class);
    private static final int BATCH = 100;

    private final UploadStore store;
    private final SessionStorage storage;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final UploadService uploads;

    UploadExpiry(
            UploadStore store,
            SessionStorage storage,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            UploadService uploads) {
        this.store = store;
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${sessions.uploads.expiry-sweep-every}")
    void sweep() {
        try {
            expire(uploads.now());
        } catch (RuntimeException e) {
            log.warn("The upload expiry sweep failed; it runs again shortly", e);
        }
    }

    /** Expires what's due at {@code now}; how many it expired. */
    int expire(Instant now) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            int expired = 0;
            for (var upload : store.expired(now, BATCH)) {
                switch (upload.status()) {
                    case PENDING ->
                        storage.abortMultipart(upload.objectKey(), Objects.requireNonNull(upload.multipartId()));
                    case READY -> {
                        if (store.inUse(upload.id())) {
                            continue;
                        }
                        storage.delete(upload.objectKey());
                    }
                    default -> {
                        continue;
                    }
                }
                store.finish(upload.id(), UploadStore.Status.EXPIRED, upload.completedAt(), now);
                expired++;
                log.info("Upload {} expired ({})", upload.id(), UploadService.status(upload));
            }
            return expired;
        }));
    }
}
