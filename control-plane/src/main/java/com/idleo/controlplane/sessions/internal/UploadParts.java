package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The parts of an upload's files (ADR 0019, ADR 0022), each file a multipart upload in storage of its own.
 *
 * <ul>
 *   <li>Links are signed per file, on demand, for the parts asked for, so a file of thousands of parts never gets
 *       thousands of links at once; the first ask opens the file's multipart upload. Each link works for an hour.
 *   <li>Completing files joins each one's parts and checks the stored size: a file can be completed as soon as it's
 *       sent, and again harmlessly. An empty file has no parts; completing it writes it empty. A finished file of the
 *       wrong size fails the whole upload, and everything stored for it is deleted.
 *   <li>The upload is ready once every file is complete.
 * </ul>
 */
@Service
class UploadParts {

    /** The most part links one request gets. */
    static final int MAX_LINKS = 100;

    private static final Logger log = LoggerFactory.getLogger(UploadParts.class);

    private final UploadStore store;
    private final UploadFileStore files;
    private final UploadService uploads;
    private final SessionStorage storage;
    private final UploadProperties properties;
    private final TransactionTemplate transactions;

    UploadParts(
            UploadStore store,
            UploadFileStore files,
            UploadService uploads,
            SessionStorage storage,
            UploadProperties properties,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.files = files;
        this.uploads = uploads;
        this.storage = storage;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A part's number and the link to PUT it to. */
    record PartLink(int number, String url) {}

    /** Links for some parts, and when they stop working. */
    record PartLinks(List<PartLink> parts, Instant expireAt) {}

    /** A file's parts as uploaded: each number with the ETag storage gave it. */
    record FileParts(String path, List<SessionStorage.PartEtag> parts) {}

    /** Links for some parts of one file of the renter's pending upload. */
    PartLinks links(UUID userId, UUID uploadId, String path, List<Integer> partNumbers) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var upload = pending(userId, uploadId, "Part links are");
            var file = file(upload, path);
            int count = file.partCount(upload.partSizeBytes());
            if (count == 0) {
                throw ApiProblem.invalid("path", "is an empty file: it has no parts; complete it with none.");
            }
            if (partNumbers.stream().anyMatch(number -> number < 1 || number > count)
                    || new HashSet<>(partNumbers).size() != partNumbers.size()) {
                throw ApiProblem.invalid("partNumbers", "must be different part numbers from 1 to " + count + ".");
            }
            var multipartId = file.multipartId();
            if (multipartId == null) {
                multipartId = storage.openMultipart(file.objectKey());
                files.opened(file, multipartId);
            }
            var ttl = properties.linkTtl();
            var expireAt = uploads.now().plus(ttl);
            var opened = multipartId;
            return new PartLinks(
                    partNumbers.stream()
                            .map(number -> new PartLink(
                                    number,
                                    storage.partLink(file.objectKey(), opened, number, ttl)
                                            .toString()))
                            .toList(),
                    expireAt);
        }));
    }

    /**
     * Completes some files of the renter's upload, and the upload once every file is. Completing a ready upload, or a
     * complete file, again changes nothing. Parts storage refuses leave the upload pending, to try again.
     */
    UploadStore.Upload complete(UUID userId, UUID uploadId, List<FileParts> completed) {
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
                        "Only a pending upload can be completed; this one is " + UploadService.status(upload) + ".");
            }
            var now = uploads.now();
            for (var given : completed) {
                var problem = completeFile(upload, file(upload, given.path()), given.parts(), now);
                if (problem != null) {
                    // Thrown after the commit, so the failed upload stays failed.
                    return new Outcome(null, problem);
                }
            }
            if (files.files(uploadId).stream().allMatch(UploadFileStore.UploadFile::done)) {
                store.finish(uploadId, UploadStore.Status.READY, now, now.plus(properties.readyTtl()));
            }
            return new Outcome(store.find(uploadId).orElseThrow(), null);
        }));
        if (outcome.problem() != null) {
            throw outcome.problem();
        }
        return Objects.requireNonNull(outcome.upload());
    }

    /** Completes one file; a problem that fails the upload, or null. Refusals storage can recover from are thrown. */
    private @Nullable ApiProblem completeFile(
            UploadStore.Upload upload,
            UploadFileStore.UploadFile file,
            List<SessionStorage.PartEtag> parts,
            Instant now) {
        if (file.done()) {
            return null;
        }
        int count = file.partCount(upload.partSizeBytes());
        var numbers =
                parts.stream().map(SessionStorage.PartEtag::number).sorted().toList();
        if (!numbers.equals(IntStream.rangeClosed(1, count).boxed().toList())) {
            throw ApiProblem.of(
                    Problem.UPLOAD_PARTS_INVALID,
                    file.path() + " has " + count + " parts: send each of 1 to " + count
                            + " once, with the ETag storage returned for it.");
        }
        if (count == 0) {
            storage.writeEmpty(file.objectKey());
            files.completed(file, now);
            return null;
        }
        var multipartId = file.multipartId();
        var sorted = parts.stream()
                .sorted(Comparator.comparingInt(SessionStorage.PartEtag::number))
                .toList();
        if (multipartId == null || !storage.completeMultipart(file.objectKey(), multipartId, sorted)) {
            throw ApiProblem.of(
                    Problem.UPLOAD_PARTS_INVALID,
                    "Storage refused the parts of " + file.path() + ": a part is missing, or an ETag doesn't match "
                            + "the part it names. Upload the missing parts and complete it again.");
        }
        long stored = storage.size(file.objectKey()).orElse(-1L);
        if (stored != file.sizeBytes()) {
            files.completed(file, now);
            uploads.removeStored(upload);
            store.finish(upload.id(), UploadStore.Status.FAILED, null, now);
            log.info(
                    "Upload {}: {} came to {} bytes, not the {} declared; failed",
                    upload.id(),
                    file.path(),
                    stored,
                    file.sizeBytes());
            return ApiProblem.of(
                    Problem.UPLOAD_PARTS_INVALID,
                    file.path() + " is " + stored + " bytes, not the " + file.sizeBytes()
                            + " declared, so the upload failed and was deleted. Start a new one.");
        }
        files.completed(file, now);
        return null;
    }

    private UploadStore.Upload pending(UUID userId, UUID uploadId, String what) {
        var upload = store.lock(uploadId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
        if (upload.status() != UploadStore.Status.PENDING) {
            throw ApiProblem.of(
                    Problem.UPLOAD_NOT_READY,
                    what + " only for a pending upload; this one is " + UploadService.status(upload) + ".");
        }
        return upload;
    }

    private UploadFileStore.UploadFile file(UploadStore.Upload upload, String path) {
        return files.lock(upload.id(), path)
                .orElseThrow(() -> ApiProblem.invalid("path", path + " isn't a file of this upload."));
    }
}
