package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Each grant is limited by storage itself, not by agents behaving (ADR 0016): real S3 calls against MinIO. */
@ControlPlaneTest
class StorageGrantScopeTests {

    @Autowired
    StorageGrants grants;

    @Autowired
    StorageProperties properties;

    private static void put(S3Client s3, String key) {
        TestS3.put(s3, key, "data");
    }

    private static String get(S3Client s3, String key) {
        return TestS3.get(s3, key);
    }

    private static void refused(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    @Test
    void theAttemptGrantWritesOnlyItsOwnAttempt() {
        var session = UUID.randomUUID();
        var upload = "uploads/" + UUID.randomUUID() + "/";
        var minted = grants.mint(session, 2, null, null);
        try (var attempt = TestS3.as(minted.attemptGrant())) {
            put(attempt, StorageLayout.attemptPrefix(session, 2) + "checkpoints/00000001/state.json");

            refused(() -> put(attempt, upload + "data.txt"));
            refused(() -> put(attempt, StorageLayout.attemptPrefix(session, 1) + "outputs/result.json"));
            refused(() -> put(attempt, StorageLayout.attemptPrefix(UUID.randomUUID(), 2) + "x"));
        }
        assertThat(minted.readGrant())
                .as("no input and no resume: no read grant")
                .isNull();
    }

    @Test
    void theReadGrantReadsTheInputAndTheResumeCheckpointAndWritesNothing() {
        var session = UUID.randomUUID();
        var upload = "uploads/" + UUID.randomUUID() + "/";
        var otherUpload = "uploads/" + UUID.randomUUID() + "/";
        try (var root = TestS3.root(properties)) {
            put(root, upload + "data.txt");
            put(root, otherUpload + "data.txt");
            put(root, StorageLayout.checkpointFolder(session, 1, 3) + "state.json");
            put(root, StorageLayout.checkpointFolder(session, 1, 2) + "state.json");
        }
        var minted = grants.mint(session, 2, new StorageGrants.ResumeFrom(1, 3), upload);

        try (var read = TestS3.as(minted.readGrant())) {
            assertThat(get(read, upload + "data.txt")).isEqualTo("data");
            assertThat(get(read, StorageLayout.checkpointFolder(session, 1, 3) + "state.json"))
                    .isEqualTo("data");

            refused(() -> get(read, StorageLayout.checkpointFolder(session, 1, 2) + "state.json"));
            refused(() -> get(read, otherUpload + "data.txt"));
            refused(() -> put(read, upload + "data.txt"));
            refused(() -> put(read, StorageLayout.attemptPrefix(session, 2) + "outputs/x"));
        }
        try (var attempt = TestS3.as(minted.attemptGrant())) {
            refused(() -> get(attempt, upload + "data.txt"));
        }
    }

    @Test
    void aGrantNeverPrintsItsSecrets() {
        var grant = grants.mint(UUID.randomUUID(), 1, null, null).attemptGrant();
        assertThat(grant.toString()).doesNotContain(grant.secretAccessKey(), grant.sessionToken());
    }
}
