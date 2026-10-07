package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uploads (ADR 0019): one file each, sent by the client straight to storage through pre-signed multipart links;
 * the control plane never carries the bytes.
 *
 * <ul>
 *   <li>Creating one opens a multipart upload and signs a link per part, valid for an hour; every part but the last
 *       is the configured part size, and a one-part upload is still multipart, so there's one way.
 *   <li>Completing it joins the parts, then checks the stored object's size against the declared size: a match
 *       makes it ready, a mismatch deletes the object and fails it.
 *   <li>A renter holds at most the quota in pending and ready uploads; each file is at most the size limit.
 *   <li>Another renter's upload is {@code not-found}; one a session that hasn't ended uses can't be deleted.
 * </ul>
 */
@Service
@EnableConfigurationProperties(UploadProperties.class)
class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadStore store;
    private final SessionStorage storage;
    private final UploadProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;

    UploadService(
            UploadStore store,
            SessionStorage storage,
            UploadProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock) {
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /** The upload, and whether this call created it ({@code false}: a repeat of the same request). */
    record Created(UploadStore.Upload upload, boolean created) {}

    /** A part's number and the link to PUT it to. */
    record PartLink(int number, String url) {}

    /** Links for some parts, and when they stop working. */
    record PartLinks(List<PartLink> parts, Instant expireAt) {}

    Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Opens an upload of {@code sizeBytes} named {@code filename}. The same key with the same request returns the
     * upload it opened; with a different request it's refused.
     */
    Created create(UUID userId, String idempotencyKey, String filename, long sizeBytes) {
        UploadNames.problem(filename).ifPresent(problem -> {
            throw ApiProblem.invalid("filename", problem);
        });
        if (sizeBytes > properties.maxFileSize().toBytes()) {
            throw ApiProblem.invalid(
                    "sizeBytes", "must be at most " + properties.maxFileSize().toBytes() + " bytes.");
        }
        var hash = hash(filename + "\n" + sizeBytes);
        return Objects.requireNonNull(transactions.execute(status -> {
            var existing = store.findByKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return repeat(existing.get(), hash);
            }
            // One create at a time per renter, so two at once can't both fit under the quota.
            jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:userId as text), 0))")
                    .param("userId", userId)
                    .query(Object.class)
                    .single();
            long held = store.held(userId);
            if (held + sizeBytes > properties.quota().toBytes()) {
                throw ApiProblem.of(
                        Problem.UPLOAD_QUOTA_EXCEEDED,
                        "This upload of " + sizeBytes + " bytes would make " + (held + sizeBytes)
                                + " bytes of pending and ready uploads; the most is "
                                + properties.quota().toBytes()
                                + ". Delete some first.");
            }
            var id = ids.newId();
            var storedName = UploadNames.stored(filename);
            var key = "uploads/" + id + "/" + storedName;
            var now = now();
            var multipartId = storage.openMultipart(key);
            var upload = new UploadStore.Upload(
                    id,
                    userId,
                    filename,
                    storedName,
                    sizeBytes,
                    key,
                    multipartId,
                    properties.partSize().toBytes(),
                    UploadStore.Status.PENDING,
                    idempotencyKey,
                    hash,
                    now,
                    null,
                    now.plus(properties.pendingTtl()),
                    0);
            if (!store.insert(upload)) {
                // A request with the same key got there first.
                storage.abortMultipart(key, multipartId);
                return repeat(store.findByKey(userId, idempotencyKey).orElseThrow(), hash);
            }
            return new Created(upload, true);
        }));
    }

    private static Created repeat(UploadStore.Upload existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw ApiProblem.of(
                    Problem.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used for a different upload.");
        }
        return new Created(existing, false);
    }

    /** Links for every part of a pending upload. */
    PartLinks links(UploadStore.Upload upload) {
        var numbers = new java.util.ArrayList<Integer>();
        for (int number = 1; number <= upload.partCount(); number++) {
            numbers.add(number);
        }
        return sign(upload, numbers);
    }

    /** Fresh links for some parts of the renter's pending upload, for uploads that outlast an hour. */
    PartLinks freshLinks(UUID userId, UUID uploadId, List<Integer> partNumbers) {
        var upload = owned(userId, uploadId);
        if (upload.status() != UploadStore.Status.PENDING) {
            throw ApiProblem.of(
                    Problem.UPLOAD_NOT_READY,
                    "Fresh part links are only for a pending upload; this one is " + status(upload) + ".");
        }
        var bad = partNumbers.stream()
                .filter(number -> number < 1 || number > upload.partCount())
                .toList();
        if (!bad.isEmpty() || new HashSet<>(partNumbers).size() != partNumbers.size()) {
            throw ApiProblem.invalid(
                    "partNumbers", "must be different part numbers from 1 to " + upload.partCount() + ".");
        }
        return sign(upload, partNumbers);
    }

    private PartLinks sign(UploadStore.Upload upload, List<Integer> numbers) {
        var multipartId = Objects.requireNonNull(upload.multipartId(), "a pending upload has a multipart upload");
        var ttl = properties.linkTtl();
        var expireAt = now().plus(ttl);
        return new PartLinks(
                numbers.stream()
                        .map(number -> new PartLink(
                                number,
                                storage.partLink(upload.objectKey(), multipartId, number, ttl)
                                        .toString()))
                        .toList(),
                expireAt);
    }

    /**
     * Completes the renter's upload from its parts. Completing a ready one again changes nothing. Parts storage
     * refuses leave it pending, to try again; a finished file of the wrong size fails it, and its object is deleted.
     */
    UploadStore.Upload complete(UUID userId, UUID uploadId, List<SessionStorage.PartEtag> parts) {
        record Outcome(
                UploadStore.@Nullable Upload upload,
                @Nullable ApiProblem problem) {}
        var outcome = Objects.requireNonNull(transactions.execute(status -> {
            var upload = store.lock(uploadId)
                    .filter(found -> found.userId().equals(userId))
                    .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
            if (upload.status() == UploadStore.Status.READY) {
                return new Outcome(upload, null);
            }
            if (upload.status() != UploadStore.Status.PENDING) {
                throw ApiProblem.of(
                        Problem.UPLOAD_NOT_READY,
                        "Only a pending upload can be completed; this one is " + status(upload) + ".");
            }
            var numbers =
                    parts.stream().map(SessionStorage.PartEtag::number).sorted().toList();
            boolean whole = numbers.equals(java.util.stream.IntStream.rangeClosed(1, upload.partCount())
                    .boxed()
                    .toList());
            if (!whole) {
                throw ApiProblem.of(
                        Problem.UPLOAD_PARTS_INVALID,
                        "This upload has " + upload.partCount() + " parts: send each of 1 to " + upload.partCount()
                                + " once, with the ETag storage returned for it.");
            }
            var sorted = parts.stream()
                    .sorted(java.util.Comparator.comparingInt(SessionStorage.PartEtag::number))
                    .toList();
            var multipartId = Objects.requireNonNull(upload.multipartId());
            if (!storage.completeMultipart(upload.objectKey(), multipartId, sorted)) {
                throw ApiProblem.of(
                        Problem.UPLOAD_PARTS_INVALID,
                        "Storage refused the parts: a part is missing, or an ETag doesn't match the part it names. "
                                + "Upload the missing parts and complete it again.");
            }
            var now = now();
            long stored = storage.size(upload.objectKey()).orElse(-1L);
            if (stored != upload.sizeBytes()) {
                storage.delete(upload.objectKey());
                store.finish(upload.id(), UploadStore.Status.FAILED, null, now);
                log.info(
                        "Upload {} came to {} bytes, not the {} declared; failed",
                        upload.id(),
                        stored,
                        upload.sizeBytes());
                return new Outcome(
                        null,
                        ApiProblem.of(
                                Problem.UPLOAD_PARTS_INVALID,
                                "The finished file is " + stored + " bytes, not the " + upload.sizeBytes()
                                        + " declared, so the upload failed and was deleted. Start a new one."));
            }
            store.finish(upload.id(), UploadStore.Status.READY, now, now.plus(properties.readyTtl()));
            return new Outcome(store.find(upload.id()).orElseThrow(), null);
        }));
        if (outcome.problem() != null) {
            // Thrown after the commit, so the failed upload stays failed.
            throw outcome.problem();
        }
        return Objects.requireNonNull(outcome.upload());
    }

    /**
     * Deletes the renter's upload: aborts it if pending, deletes its object if ready. Refused while a session that
     * hasn't ended uses it. Deleting one that's already gone changes nothing.
     */
    void delete(UUID userId, UUID uploadId) {
        transactions.executeWithoutResult(status -> {
            var upload = store.lock(uploadId)
                    .filter(found -> found.userId().equals(userId))
                    .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
            if (store.inUse(upload.id())) {
                throw ApiProblem.of(
                        Problem.UPLOAD_IN_USE,
                        "A session that hasn't ended uses this upload as its input; delete it once that session ends.");
            }
            switch (upload.status()) {
                case PENDING ->
                    storage.abortMultipart(upload.objectKey(), Objects.requireNonNull(upload.multipartId()));
                case READY -> storage.delete(upload.objectKey());
                case FAILED, EXPIRED, DELETED -> {
                    return;
                }
            }
            store.finish(upload.id(), UploadStore.Status.DELETED, upload.completedAt(), now());
        });
    }

    UploadStore.Upload owned(UUID userId, UUID uploadId) {
        return store.find(uploadId)
                .filter(upload -> upload.userId().equals(userId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
    }

    static String status(UploadStore.Upload upload) {
        return upload.status().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String hash(String canonical) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
