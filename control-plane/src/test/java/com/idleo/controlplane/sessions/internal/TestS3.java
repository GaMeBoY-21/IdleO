package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.TestStorageConfiguration;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** S3 clients for storage tests: as a grant's holder, or as MinIO's root. */
final class TestS3 {

    private TestS3() {}

    static S3Client as(StorageGrant grant) {
        return client(
                grant.endpoint(),
                AwsSessionCredentials.create(grant.accessKeyId(), grant.secretAccessKey(), grant.sessionToken()));
    }

    /** As an agent holding a grant from a StartSession. */
    static S3Client as(gpu.agent.v1.Session.StorageGrant grant) {
        return client(
                grant.getEndpoint(),
                AwsSessionCredentials.create(
                        grant.getAccessKeyId(), grant.getSecretAccessKey(), grant.getSessionToken()));
    }

    static S3Client root(StorageProperties properties) {
        return client(
                properties.endpoint().toString(),
                AwsBasicCredentials.create(TestStorageConfiguration.ROOT_USER, TestStorageConfiguration.ROOT_PASSWORD));
    }

    static void put(S3Client s3, String key, String content) {
        s3.putObject(
                request -> request.bucket(TestStorageConfiguration.BUCKET).key(key), RequestBody.fromString(content));
    }

    static String get(S3Client s3, String key) {
        return s3.getObjectAsBytes(request ->
                        request.bucket(TestStorageConfiguration.BUCKET).key(key))
                .asUtf8String();
    }

    private static S3Client client(String endpoint, AwsCredentials credentials) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
