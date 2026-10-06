package com.idleo.controlplane.sessions.internal;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * What the control plane itself does in storage, with its own key (ADR 0016): check a completion marker
 * exists, list a folder, and sign a renter's download link. It never trusts an agent's word that a
 * checkpoint or the outputs are complete.
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

    record StoredObject(String key, long size) {}

    boolean exists(String key) {
        try {
            s3.headObject(request -> request.bucket(properties.bucket()).key(key));
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    List<StoredObject> list(String prefix) {
        var objects = new ArrayList<StoredObject>();
        for (var page : s3.listObjectsV2Paginator(
                request -> request.bucket(properties.bucket()).prefix(prefix))) {
            page.contents().forEach(object -> objects.add(new StoredObject(object.key(), object.size())));
        }
        return objects;
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
