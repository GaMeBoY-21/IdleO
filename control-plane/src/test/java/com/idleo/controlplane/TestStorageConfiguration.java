package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Object storage for tests: MinIO, the same image as compose.yaml, its STS minting real scoped grants. */
@TestConfiguration(proxyBeanMethods = false)
public class TestStorageConfiguration {

    /** Same image as compose.yaml; {@link ComposeImageTests} keeps the two identical. */
    static final String MINIO_IMAGE =
            "pgsty/minio:RELEASE.2026-08-04T00-00-00Z@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372";

    public static final String ROOT_USER = "test-storage-root";
    public static final String ROOT_PASSWORD = "test-storage-password";
    public static final String BUCKET = "idleo-test";

    /** MinIO, as its own type so it never competes with the other containers for injection. */
    public static class StorageContainer extends GenericContainer<StorageContainer> {

        StorageContainer() {
            super(DockerImageName.parse(MINIO_IMAGE));
            withCommand("server", "/data");
            withEnv("MINIO_ROOT_USER", ROOT_USER);
            withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD);
            withExposedPorts(9000);
            waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        }

        public String endpoint() {
            return "http://" + getHost() + ":" + getMappedPort(9000);
        }
    }

    @Bean
    StorageContainer storage() {
        return new StorageContainer();
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(StorageContainer storage) {
        return registry -> {
            registry.add("sessions.storage.provider", () -> "minio");
            registry.add("sessions.storage.endpoint", storage::endpoint);
            registry.add("sessions.storage.bucket", () -> BUCKET);
            registry.add("sessions.storage.region", () -> "us-east-1");
            registry.add("sessions.storage.access-key-id", () -> ROOT_USER);
            registry.add("sessions.storage.secret-access-key", () -> ROOT_PASSWORD);
        };
    }
}
