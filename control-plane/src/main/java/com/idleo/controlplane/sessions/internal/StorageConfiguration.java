package com.idleo.controlplane.sessions.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sts.StsClient;
import tools.jackson.databind.json.JsonMapper;

/** Object storage wiring (ADR 0016): the grant issuer for the configured provider, and S3 clients. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
    }

    /** Path-style addressing works on both MinIO and R2, and keeps the bucket out of the host name. */
    private static S3Configuration pathStyle() {
        return S3Configuration.builder().pathStyleAccessEnabled(true).build();
    }

    @Bean(destroyMethod = "close")
    S3Client sessionS3(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(pathStyle())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner sessionS3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(pathStyle())
                .build();
    }

    @Bean
    SessionStorage sessionStorage(S3Client sessionS3, S3Presigner sessionS3Presigner, StorageProperties properties) {
        var storage = new SessionStorage(sessionS3, sessionS3Presigner, properties);
        if (properties.provider() == StorageProperties.Provider.MINIO) {
            storage.ensureBucketForDevelopment();
        }
        return storage;
    }

    @Bean
    GrantIssuer grantIssuer(StorageProperties properties, JsonMapper json) {
        return switch (properties.provider()) {
            case MINIO ->
                new MinioGrantIssuer(
                        StsClient.builder()
                                .endpointOverride(properties.endpoint())
                                .region(Region.of(properties.region()))
                                .credentialsProvider(credentials(properties))
                                .httpClient(UrlConnectionHttpClient.create())
                                .build(),
                        properties,
                        json);
            case R2 -> new R2GrantIssuer(RestClient.builder(), properties);
        };
    }
}
