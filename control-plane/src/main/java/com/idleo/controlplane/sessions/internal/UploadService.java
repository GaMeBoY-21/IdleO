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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uploads (ADR 0019, ADR 0022): a renter's folder of files, or one file, sent by the client straight to storage
 * through pre-signed multipart links; the control plane never carries the bytes. Kept in My files and reused across
 * sessions.
 *
 * <ul>
 *   <li>Creating one records its files, their relative paths and sizes, and opens nothing in storage yet; each file's
 *       multipart upload opens when its first part links are asked for ({@link UploadParts}).
 *   <li>Paths are relative, without {@code ..}, at most the configured number of files; each segment is stored as an
 *       upload's name is, and two paths that would be stored alike, or a file where another's folder is, are refused.
 *   <li>A renter holds at most the quota in pending and ready uploads; each upload, its files together, is at most
 *       the size limit.
 *   <li>Another renter's upload is {@code not-found}; one a session that hasn't ended uses can't be deleted.
 * </ul>
 */
@Service
@EnableConfigurationProperties(UploadProperties.class)
class UploadService {

    private final UploadStore store;
    private final UploadFileStore files;
    private final SessionStorage storage;
    private final UploadProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;

    UploadService(
            UploadStore store,
            UploadFileStore files,
            SessionStorage storage,
            UploadProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock) {
        this.store = store;
        this.files = files;
        this.storage = storage;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /** The upload, and whether this call created it ({@code false}: a repeat of the same request). */
    record Created(UploadStore.Upload upload, boolean created) {}

    /** A file to upload: its path relative to the upload, and its size. */
    record FileSpec(String path, long sizeBytes) {}

    Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Opens an upload named {@code name} of {@code specs}. The same key with the same request returns the upload it
     * opened; with a different request it's refused.
     */
    Created create(UUID userId, String idempotencyKey, String name, List<FileSpec> specs) {
        UploadNames.problem(name).ifPresent(problem -> {
            throw ApiProblem.invalid("name", problem);
        });
        var stored = check(specs);
        long total = specs.stream().mapToLong(FileSpec::sizeBytes).sum();
        if (total > properties.maxUploadSize().toBytes()) {
            throw ApiProblem.invalid(
                    "files",
                    "come to " + total + " bytes; an upload is at most "
                            + properties.maxUploadSize().toBytes() + " bytes.");
        }
        var canonical = new StringBuilder(name);
        specs.stream()
                .sorted(Comparator.comparing(FileSpec::path))
                .forEach(spec ->
                        canonical.append('\n').append(spec.path()).append('\0').append(spec.sizeBytes()));
        var hash = hash(canonical.toString());
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
            if (held + total > properties.quota().toBytes()) {
                throw ApiProblem.of(
                        Problem.UPLOAD_QUOTA_EXCEEDED,
                        "This upload of " + total + " bytes would make " + (held + total)
                                + " bytes of pending and ready uploads; the most is "
                                + properties.quota().toBytes() + ". Delete some in My files first.");
            }
            var id = ids.newId();
            var now = now();
            var upload = new UploadStore.Upload(
                    id,
                    userId,
                    name,
                    total,
                    specs.size(),
                    properties.partSize().toBytes(),
                    UploadStore.Status.PENDING,
                    idempotencyKey,
                    hash,
                    now,
                    null,
                    null,
                    now.plus(properties.pendingTtl()),
                    0);
            if (!store.insert(upload)) {
                // A request with the same key got there first.
                return repeat(store.findByKey(userId, idempotencyKey).orElseThrow(), hash);
            }
            var rows = new ArrayList<UploadFileStore.UploadFile>();
            for (int i = 0; i < specs.size(); i++) {
                var spec = specs.get(i);
                rows.add(new UploadFileStore.UploadFile(
                        id,
                        spec.path(),
                        stored.get(i),
                        spec.sizeBytes(),
                        StorageLayout.uploadFile(id, stored.get(i)),
                        null,
                        null));
            }
            files.insert(rows);
            return new Created(upload, true);
        }));
    }

    /** Each file's stored path, in order; refuses bad paths, too many files, and paths that clash once stored. */
    private List<String> check(List<FileSpec> specs) {
        if (specs.isEmpty() || specs.size() > properties.maxFiles()) {
            throw ApiProblem.invalid("files", "must be 1 to " + properties.maxFiles() + " files.");
        }
        var stored = new ArrayList<String>();
        var seen = new TreeSet<String>();
        for (int i = 0; i < specs.size(); i++) {
            var spec = specs.get(i);
            var field = "files[" + i + "].path";
            UploadNames.pathProblem(spec.path()).ifPresent(problem -> {
                throw ApiProblem.invalid(field, problem);
            });
            if (spec.sizeBytes() < 0) {
                throw ApiProblem.invalid("files[" + i + "].sizeBytes", "can't be below zero.");
            }
            var path = UploadNames.storedPath(spec.path());
            if (!seen.add(path)) {
                throw ApiProblem.invalid(field, "would be stored as " + path + ", like another file.");
            }
            stored.add(path);
        }
        for (var path : seen) {
            var folder = path + "/";
            var next = seen.higher(path);
            if (next != null && next.startsWith(folder)) {
                throw ApiProblem.invalid("files", path + " is a file and also a folder of " + next + ".");
            }
        }
        return stored;
    }

    private static Created repeat(UploadStore.Upload existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw ApiProblem.of(
                    Problem.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used for a different upload.");
        }
        return new Created(existing, false);
    }

    /**
     * Deletes the renter's upload: aborts its files' open multipart uploads and deletes what's stored. Refused while a
     * session that hasn't ended uses it. Deleting one that's already gone changes nothing.
     */
    void delete(UUID userId, UUID uploadId) {
        transactions.executeWithoutResult(status -> {
            var upload = store.lock(uploadId)
                    .filter(found -> found.userId().equals(userId))
                    .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
            if (store.inUse(upload.id())) {
                throw ApiProblem.of(
                        Problem.UPLOAD_IN_USE,
                        "A session that hasn't ended uses this upload as an input; delete it once that session ends.");
            }
            if (upload.status() != UploadStore.Status.PENDING && upload.status() != UploadStore.Status.READY) {
                return;
            }
            removeStored(upload);
            store.finish(upload.id(), UploadStore.Status.DELETED, upload.completedAt(), now());
        });
    }

    /** Aborts every file's open multipart upload and deletes every stored file: for delete, expiry and failure. */
    void removeStored(UploadStore.Upload upload) {
        for (var file : files.files(upload.id())) {
            if (file.multipartId() != null) {
                storage.abortMultipart(file.objectKey(), file.multipartId());
            }
            if (file.done()) {
                storage.delete(file.objectKey());
            }
        }
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
