package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestStorageConfiguration;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Each grant is limited by storage itself, not by agents behaving (ADR 0016): real S3 calls against MinIO. */
@ControlPlaneTest
class StorageGrantScopeTests {

    @Autowired
    StorageGrants grants;

    @Autowired
    StorageProperties properties;

    private S3Client client(StorageGrant grant) {
        return S3Client.builder()
                .endpointOverride(URI.create(grant.endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        grant.accessKeyId(), grant.secretAccessKey(), grant.sessionToken())))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private S3Client root() {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        TestStorageConfiguration.ROOT_USER, TestStorageConfiguration.ROOT_PASSWORD)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private void put(S3Client s3, String key) {
        s3.putObject(request -> request.bucket(properties.bucket()).key(key), RequestBody.fromString("data"));
    }

    private String get(S3Client s3, String key) {
        return s3.getObjectAsBytes(
                        request -> request.bucket(properties.bucket()).key(key))
                .asUtf8String();
    }

    private static void refused(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    @Test
    void theAttemptGrantWritesOnlyItsOwnAttempt() {
        var session = UUID.randomUUID();
        var minted = grants.mint(session, 2, null, false);
        try (var attempt = client(minted.attemptGrant())) {
            put(attempt, StorageLayout.attemptPrefix(session, 2) + "checkpoints/00000001/state.json");

            refused(() -> put(attempt, StorageLayout.inputPrefix(session) + "data.txt"));
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
        try (var root = root()) {
            put(root, StorageLayout.inputPrefix(session) + "data.txt");
            put(root, StorageLayout.checkpointFolder(session, 1, 3) + "state.json");
            put(root, StorageLayout.checkpointFolder(session, 1, 2) + "state.json");
        }
        var minted = grants.mint(session, 2, new StorageGrants.ResumeFrom(1, 3), true);

        try (var read = client(minted.readGrant())) {
            assertThat(get(read, StorageLayout.inputPrefix(session) + "data.txt"))
                    .isEqualTo("data");
            assertThat(get(read, StorageLayout.checkpointFolder(session, 1, 3) + "state.json"))
                    .isEqualTo("data");

            refused(() -> get(read, StorageLayout.checkpointFolder(session, 1, 2) + "state.json"));
            refused(() -> put(read, StorageLayout.inputPrefix(session) + "data.txt"));
            refused(() -> put(read, StorageLayout.attemptPrefix(session, 2) + "outputs/x"));
        }
        try (var attempt = client(minted.attemptGrant())) {
            refused(() -> get(attempt, StorageLayout.inputPrefix(session) + "data.txt"));
        }
    }

    @Test
    void aGrantNeverPrintsItsSecrets() {
        var grant = grants.mint(UUID.randomUUID(), 1, null, false).attemptGrant();
        assertThat(grant.toString()).doesNotContain(grant.secretAccessKey(), grant.sessionToken());
    }
}
