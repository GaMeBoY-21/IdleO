package com.idleo.controlplane.sessions.internal;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * What the control plane itself does in storage, with its own key (ADR 0016): check a completion marker
 * exists, list a folder, read a log chunk, and sign a renter's download link; and for uploads (ADR 0019), open,
 * sign, complete and abort multipart uploads, and delete objects. It never trusts an agent's word that a
 * checkpoint or the outputs are complete, and never carries an upload's bytes.
 */
class SessionStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    SessionStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    /** @param lastModified when storage finished writing it, on storage's clock, never an agent's */
    record StoredObject(String key, long size, Instant lastModified) {}

    boolean exists(String key) {
        return lastModified(key).isPresent();
    }

    /**
     * When storage finished writing {@code key}, on storage's clock; empty if it isn't there. From a listing,
     * which gives milliseconds, where a HEAD's Last-Modified gives only whole seconds.
     */
    Optional<Instant> lastModified(String key) {
        return s3
                .listObjectsV2(request ->
                        request.bucket(properties.bucket()).prefix(key).maxKeys(1))
                .contents()
                .stream()
                .filter(object -> object.key().equals(key))
                .findFirst()
                .map(object -> object.lastModified());
    }

    List<StoredObject> list(String prefix) {
        var objects = new ArrayList<StoredObject>();
        for (var page : s3.listObjectsV2Paginator(
                request -> request.bucket(properties.bucket()).prefix(prefix))) {
            page.contents()
                    .forEach(object ->
                            objects.add(new StoredObject(object.key(), object.size(), object.lastModified())));
        }
        return objects;
    }

    /** Opens a multipart upload at {@code key}; its id. */
    String openMultipart(String key) {
        return s3.createMultipartUpload(
                        request -> request.bucket(properties.bucket()).key(key))
                .uploadId();
    }

    /** A link a client PUTs part {@code partNumber} to, for {@code ttl}. */
    URL partLink(String key, String multipartId, int partNumber, Duration ttl) {
        return presigner
                .presignUploadPart(request -> request.signatureDuration(ttl)
                        .uploadPartRequest(part -> part.bucket(properties.bucket())
                                .key(key)
                                .uploadId(multipartId)
                                .partNumber(partNumber)))
                .url();
    }

    /** A part as the client uploaded it: its number and the ETag storage gave it. */
    record PartEtag(int number, String etag) {}

    /**
     * Joins the parts into the object; false if storage refuses them (a part missing, or an ETag that doesn't
     * match), leaving the multipart upload open to try again.
     */
    boolean completeMultipart(String key, String multipartId, List<PartEtag> parts) {
        try {
            s3.completeMultipartUpload(request -> request.bucket(properties.bucket())
                    .key(key)
                    .uploadId(multipartId)
                    .multipartUpload(upload -> upload.parts(parts.stream()
                            .map(part -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                                    .partNumber(part.number())
                                    .eTag(part.etag())
                                    .build())
                            .toList())));
            return true;
        } catch (S3Exception refused) {
            if (refused.statusCode() >= 500) {
                throw refused;
            }
            log.info(
                    "Storage refused to complete multipart upload of {}: {}",
                    key,
                    refused.awsErrorDetails() == null
                            ? refused.statusCode()
                            : refused.awsErrorDetails().errorCode());
            return false;
        }
    }

    /** Aborts a multipart upload, dropping its parts; nothing if it's already gone. */
    void abortMultipart(String key, String multipartId) {
        try {
            s3.abortMultipartUpload(
                    request -> request.bucket(properties.bucket()).key(key).uploadId(multipartId));
        } catch (S3Exception gone) {
            if (gone.statusCode() != 404) {
                throw gone;
            }
        }
    }

    /** The object's size, from storage; empty if it isn't there. */
    Optional<Long> size(String key) {
        try {
            return Optional.of(
                    s3.headObject(request -> request.bucket(properties.bucket()).key(key))
                            .contentLength());
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) {
                return Optional.empty();
            }
            throw missing;
        }
    }

    /** {@code length} bytes of an object from {@code from}: a range request, for reading the end of a large file. */
    byte[] readRange(String key, long from, int length) {
        return s3.getObjectAsBytes(request ->
                        request.bucket(properties.bucket()).key(key).range("bytes=" + from + "-" + (from + length - 1)))
                .asByteArray();
    }

    /** Reads an object whole; empty if it isn't there. For small objects, such as log chunks. */
    Optional<byte[]> read(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(
                            request -> request.bucket(properties.bucket()).key(key))
                    .asByteArray());
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) {
                return Optional.empty();
            }
            throw missing;
        }
    }

    void delete(String key) {
        s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
    }

    /** Writes an empty object: an uploaded file of zero bytes, which has no parts to send (ADR 0022). */
    void writeEmpty(String key) {
        s3.putObject(
                request -> request.bucket(properties.bucket()).key(key),
                software.amazon.awssdk.core.sync.RequestBody.empty());
    }

    URL downloadLink(String key, Duration ttl) {
        return presigner
                .presignGetObject(request -> request.signatureDuration(ttl)
                        .getObjectRequest(get -> get.bucket(properties.bucket()).key(key)))
                .url();
    }

    /** Local runs and tests only: create the bucket if it's missing. Production's bucket is made by hand. */
    void ensureBucketForDevelopment() {
        try {
            s3.headBucket(request -> request.bucket(properties.bucket()));
        } catch (S3Exception missing) {
            try {
                s3.createBucket(request -> request.bucket(properties.bucket()));
                log.info("Created the development bucket {}", properties.bucket());
            } catch (S3Exception e) {
                log.warn("Couldn't create the development bucket {}: {}", properties.bucket(), e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("Object storage isn't reachable yet: {}", e.getMessage());
        }
    }
}
